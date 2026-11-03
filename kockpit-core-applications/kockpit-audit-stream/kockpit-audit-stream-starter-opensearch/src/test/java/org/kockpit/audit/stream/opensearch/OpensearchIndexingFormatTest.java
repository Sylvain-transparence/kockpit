package org.kockpit.audit.stream.opensearch;

import org.junit.jupiter.api.Test;
import org.kockpit.audit.stream.api.model.Audit;
import org.kockpit.audit.stream.api.model.AuditReport;
import org.junit.jupiter.api.DisplayName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verrouille le format des documents indexes dans OpenSearch.
 *
 * <p>Ce format tient a trois reglages du mapper d'indexation. Rien ne les couvrait : une
 * migration ou un nettoyage qui les retirerait changerait le {@code _source} des documents sans
 * faire echouer aucun test.
 *
 * <p>Le meme contrat est porte par un second mapper, celui d'
 * {@code aws-opensearch-request-signing}, utilise sous profil {@code aws}. Les deux modules
 * n'ont pas d'ancetre commun, donc pas de fabrique partagee : ce test verifie le chemin par
 * defaut, et sert de reference si les deux configurations divergent a nouveau.
 */
class OpensearchIndexingFormatTest {

    private final ObjectMapper objectMapper =
            new OpensearchAuditConsumerConfiguration().opensearchObjectMapper();

    @Test
    @DisplayName("Les Instant sont indexes en ISO-8601 avec precision nanoseconde, pas en epoch-millis")
    void writes_instants_as_iso8601() {
        AuditReport report = new AuditReport();
        report.setStart(Instant.ofEpochSecond(1750197974L, 47081776));

        assertThat(objectMapper.writeValueAsString(report))
                .contains("\"start\":\"2025-06-17T")
                .contains("047081776")
                .doesNotContain("1750197974047")
                .doesNotContain("1750197974.047081776");
    }

    @Test
    @DisplayName("L'ordre de declaration des champs est conserve, pas l'ordre alphabetique")
    void keeps_declaration_order() {
        AuditReport report = new AuditReport();
        report.setId("abc");
        report.setDomain("kockpit");

        String json = objectMapper.writeValueAsString(report);

        assertThat(json.indexOf("\"id\""))
                .as("id est declare avant domain, donc doit apparaitre avant")
                .isLessThan(json.indexOf("\"domain\""));
        assertThat(json.indexOf("\"domain\""))
                .as("appId est declare apres domain malgre l'ordre alphabetique inverse")
                .isLessThan(json.indexOf("\"appId\""));
    }

    @Test
    @DisplayName("Les BigDecimal des evenements libres sont ecrits en notation decimale")
    void writes_big_decimals_in_plain_notation() {
        Audit audit = new Audit();
        audit.setType("builtin.web");
        audit.setEvents(List.of(Map.of("amount", new BigDecimal("0.00000001"))));

        AuditReport report = new AuditReport();
        report.setAudits(List.of(audit));

        assertThat(objectMapper.writeValueAsString(report))
                .contains("0.00000001")
                .doesNotContain("1E-8");
    }
}
