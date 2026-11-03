package org.kockpit.backend.services.search.opensearch;

import org.junit.jupiter.api.Test;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.MatchPhraseQueryBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class OpensearchRepositoryByIdTest {

    @Test
    void id_env_and_domain_must_match_as_whole_phrases() {
        BoolQueryBuilder query = OpensearchRepository.byId("platform", "dev", "078b72dd-1f13-4bbc-b4ce-8305075c3ecc");

        // a plain match on a text-mapped id would OR its tokens and match other audits' ids
        assertThat(query.must())
                .allSatisfy(q -> assertThat(q).isInstanceOf(MatchPhraseQueryBuilder.class))
                .extracting(q -> ((MatchPhraseQueryBuilder) q).fieldName() + "=" + ((MatchPhraseQueryBuilder) q).value())
                .containsExactly("id=078b72dd-1f13-4bbc-b4ce-8305075c3ecc", "env=dev", "domain=platform");
        assertThat(query.should()).isEmpty();
    }
}
