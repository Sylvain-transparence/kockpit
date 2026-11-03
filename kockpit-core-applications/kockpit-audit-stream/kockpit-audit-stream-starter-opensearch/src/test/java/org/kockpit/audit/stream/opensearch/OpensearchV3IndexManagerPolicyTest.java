package org.kockpit.audit.stream.opensearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The generic OpenSearch client returns 4xx/5xx as a Response instead of throwing, so a missing
 * ISM policy is a plain 404. Only creating the policy from a catch block meant it was never
 * created in normal operation (prod ended up with no audit_ism_policy_* at all), and the
 * rollover-based v1 policy left every daily index stuck in "hot", never deleted.
 */
class OpensearchV3IndexManagerPolicyTest {

    private static final String POLICY_ID = "audit_ism_policy_offers-auditdata-pro-ttl30d";

    private final OpenSearchClient client = mock(OpenSearchClient.class);
    private final OpenSearchGenericClient generic = mock(OpenSearchGenericClient.class);
    private final OpensearchV3IndexManager manager = new OpensearchV3IndexManager(
            client, false, "/opensearch/audit_index_template.json", "/opensearch/audit_ism_policy.json");

    @BeforeEach
    void setUp() {
        when(client.generic()).thenReturn(generic);
    }

    @Test
    @DisplayName("Une policy absente (404) est creee")
    void creates_the_policy_when_get_returns_404() throws IOException {
        Response notFound = response(404, "{}");
        Response created = response(200, "{}");
        when(generic.execute(any())).thenReturn(notFound, created);

        manager.createISMPolicy(30, POLICY_ID, "offers-auditdata-pro-ttl30d");

        List<Request> requests = requests(2);
        assertThat(requests.get(1).getMethod()).isEqualTo("PUT");
        assertThat(requests.get(1).getEndpoint()).endsWith("_plugins/_ism/policies/" + POLICY_ID);
        assertThat(requests.get(1).getParameters()).isEmpty();
        assertThat(body(requests.get(1)))
                .contains(OpensearchV3IndexManager.POLICY_VERSION_MARKER)
                .contains("\"min_index_age\": \"30d\"")
                .contains("offers-auditdata-pro-ttl30d*")
                .doesNotContain("\"rollover\"");
    }

    @Test
    @DisplayName("Une policy deja a jour n'est pas reecrite")
    void leaves_an_up_to_date_policy_alone() throws IOException {
        Response upToDate = response(200,
                "{\"_id\":\"x\",\"_seq_no\":7,\"_primary_term\":2,\"policy\":{\"description\":\""
                        + OpensearchV3IndexManager.POLICY_VERSION_MARKER + ": ...\"}}");
        when(generic.execute(any())).thenReturn(upToDate);

        manager.createISMPolicy(30, POLICY_ID, "offers-auditdata-pro-ttl30d");

        assertThat(requests(1).get(0).getMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("Une ancienne version (avec rollover) est mise a jour avec controle de concurrence")
    void updates_an_outdated_policy_with_optimistic_concurrency() throws IOException {
        Response outdated = response(200, "{\"_id\":\"x\",\"_seq_no\":7,\"_primary_term\":2,"
                + "\"policy\":{\"description\":\"Rollover policy based on 30GB size or 1 day age\"}}");
        Response updated = response(200, "{}");
        when(generic.execute(any())).thenReturn(outdated, updated);

        manager.createISMPolicy(30, POLICY_ID, "offers-auditdata-pro-ttl30d");

        Request update = requests(2).get(1);
        assertThat(update.getMethod()).isEqualTo("PUT");
        assertThat(update.getParameters()).containsEntry("if_seq_no", "7").containsEntry("if_primary_term", "2");
        assertThat(body(update)).contains(OpensearchV3IndexManager.POLICY_VERSION_MARKER).doesNotContain("\"rollover\"");
    }

    @Test
    @DisplayName("Une erreur transitoire sur le GET ne declenche pas de creation")
    void does_not_create_when_the_check_itself_fails() throws IOException {
        when(generic.execute(any())).thenThrow(new IllegalStateException("Connection pool shut down"));

        manager.createISMPolicy(30, POLICY_ID, "offers-auditdata-pro-ttl30d");

        assertThat(requests(1).get(0).getMethod()).isEqualTo("GET");
    }

    private List<Request> requests(int expected) throws IOException {
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(generic, times(expected)).execute(captor.capture());
        return captor.getAllValues();
    }

    private static String body(Request request) {
        return request.getBody().map(Body::bodyAsString).orElse("");
    }

    private static Response response(int status, String json) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        when(response.getBody()).thenReturn(Optional.of(Body.from(json.getBytes(), "application/json")));
        return response;
    }
}
