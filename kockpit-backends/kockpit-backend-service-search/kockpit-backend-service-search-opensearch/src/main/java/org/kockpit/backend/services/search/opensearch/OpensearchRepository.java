package org.kockpit.backend.services.search.opensearch;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.search.join.ScoreMode;
import org.kockpit.backend.services.search.Page;
import org.kockpit.backend.services.search.SearchService;
import org.kockpit.backend.services.search.SearchTerm;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.NestedQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.FieldSortBuilder;
import org.opensearch.search.sort.SortOrder;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static org.kockpit.backend.services.search.opensearch.AuditReportHelper.getAuditAliasName;
import static org.opensearch.index.query.QueryBuilders.*;

@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class OpensearchRepository implements SearchService {

    private final RestHighLevelClient client;

    private final String index;

    private final AuditArchiveReader auditArchiveReader;

    private final boolean wrapIndexedKeyValues;

    @SneakyThrows
    @Override
    public Object getAudit(String domain, String env, String id) {
        log.trace("load audit for domain {}, env {}, id {}", domain, env, id);
        SearchSourceBuilder searchSourceBuilder =
                new SearchSourceBuilder()
                        .query(byId(domain, env, id))
                        .sort(new FieldSortBuilder("start").order(SortOrder.DESC))
                        .size(1);

        SearchRequest searchRequest = new SearchRequest()
                .source(searchSourceBuilder)
                .indices(getAuditAliasName(domain, index, env));

        SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
        Map<String, Object> source = normalize(searchResponse.getHits().getHits()[0].getSourceAsMap());
        hydrateArchivedAudits(source);
        return source;
    }

    /**
     * Exact match on id/env/domain whatever their mapping. The templates map them as keyword, but
     * an index created without the template maps them as analyzed text: a plain match then splits
     * the id into tokens and ORs them - "078b72dd-1f13-4bbc-..." matched any audit with a "4bbc"
     * in its id, and the newest such audit was returned instead. match_phrase needs every token,
     * in order, on text, and is an exact term query on keyword.
     */
    static BoolQueryBuilder byId(String domain, String env, String id) {
        return new BoolQueryBuilder()
                .must(matchPhraseQuery("id", id))
                .must(matchPhraseQuery("env", env))
                .must(matchPhraseQuery("domain", domain));
    }

    @SneakyThrows
    @Override
    public Page listAudits(String domain, String env, List<String> appIds, Integer start, Integer size) {
        // exact, as in byId(): a plain match on a text-mapped field would OR the tokens, so
        // domain "acme" would also match "acme-test" documents
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.must(matchPhraseQuery("env", env));
        boolQueryBuilder.must(matchPhraseQuery("domain", domain));

        return runQuery(restrictToAppIds(boolQueryBuilder, appIds), domain, env, start, size);
    }

    @Override
    public Page searchByQuery(String domain, String env, String query, List<String> appIds, Integer start, Integer size) {
        return this.searchAudits(domain, env, query, null, appIds, start, size);
    }

    @SneakyThrows
    @Override
    public Page searchAudits(String domain, String env, String query, List<SearchTerm> searchTerms, List<String> appIds, Integer start, Integer size) {
        BoolQueryBuilder rootBoolQueryBuilder = new BoolQueryBuilder();
        if (!CollectionUtils.isEmpty(searchTerms)) {
            log.debug("Search terms {}", searchTerms);
            buildQuery(searchTerms, rootBoolQueryBuilder);
        }

        Optional.ofNullable(query)
                .map(String::toLowerCase)
                .map(q -> Arrays.stream(q.split(" ")))
                .ifPresent(texts -> texts.forEach(text -> {
                    if (text.contains("*")) {
                        rootBoolQueryBuilder
                                .should(wildcardQuery("audits.events.httpAuditedRequest.body", text))
                                .should(wildcardQuery("audits.events.httpAuditedResponse.body", text))
                                .should(wildcardQuery("audits.events.httpAuditedResponse.payload", text));
                    } else {
                        rootBoolQueryBuilder.should(multiMatchQuery(text, "*"));
                    }
                }));

        return runQuery(restrictToAppIds(rootBoolQueryBuilder, appIds), domain, env, start, size);
    }

    /**
     * Wraps the query rather than adding a clause to it: a bool query whose only clauses are
     * should (the free-text search) requires one of them to match, but once it also has a
     * must/filter clause its should clauses become optional - adding the appId filter in place
     * would make the free text match everything.
     */
    static BoolQueryBuilder restrictToAppIds(BoolQueryBuilder query, List<String> appIds) {
        List<String> ids = appIds == null ? List.of() : appIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (ids.isEmpty()) {
            return query;
        }
        BoolQueryBuilder appIdQuery = new BoolQueryBuilder();
        ids.forEach(id -> appIdQuery.should(termQuery("appId", id)));
        return new BoolQueryBuilder()
                .filter(appIdQuery)
                .must(query);
    }

    private void buildQuery(List<SearchTerm> searchTerms, BoolQueryBuilder rootBoolQueryBuilder) {
        // group searchTerms by name/path
        searchTerms.stream().collect(Collectors.groupingBy(searchTerm -> isNull(searchTerm.getPath()) ? searchTerm.getName() : searchTerm.getPath()))
                .forEach((path, terms) -> {
                    // get values
                    List<Object> values = new ArrayList<>();
                    terms.stream().map(SearchTerm::getValue).forEach(value -> {
                        if (value instanceof List<?> list) {
                            values.addAll(list);
                        } else {
                            values.add(value);
                        }
                    });
                    this.buildQueryWithSamePath(path, values, rootBoolQueryBuilder);
                });
    }

    private void buildQueryWithSamePath(String path, List<Object> values, BoolQueryBuilder rootBoolQueryBuilder) {
        log.trace("search on path {}, for values {}", path, values);
        if (path.contains(",")) {
            BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
            Stream.of(path.split(",")).map(String::trim)
                    .forEach(p -> {
                        if (p.startsWith("indexedKeyValues")) {
                            String key = p.substring("indexedKeyValues".length() + 1);
                            boolQueryBuilder.should(buildQueryForIndexedKeyValues(key, values));
                        } else {
                            boolQueryBuilder.should(buildValuesQuery(p, values));
                        }
                    });
            rootBoolQueryBuilder.must(boolQueryBuilder);
        } else {
            // indexedKeyValues -> nested search
            if (path.startsWith("indexedKeyValues")) {
                log.trace("nested search for indexedKeyValues on path {}", path);
                String key = path.substring("indexedKeyValues".length() + 1);
                NestedQueryBuilder nestedQueryBuilder = buildQueryForIndexedKeyValues(key, values);
                rootBoolQueryBuilder.must(nestedQueryBuilder);

            } else if ("start".equals(path) || "end".equals(path)) {
                log.trace("time-base search on path {}", path);
                // timestamp search
                long value = (long) values.get(0);
                if ("start".equals(path)) {
                    rootBoolQueryBuilder.must(rangeQuery(path).gte(value));
                }
                if ("end".equals(path)) {
                    rootBoolQueryBuilder.must(rangeQuery(path).lt(value));
                }
            } else {
                log.trace("basic search on path {}", path);
                rootBoolQueryBuilder.must(buildValuesQuery(path, values));
            }
        }
    }

    private static boolean isWildcard(Object value) {
        return value instanceof String s && s.contains("*");
    }

    /**
     * Builds the query for a single value on a given field. When the value contains one or more
     * '*', a wildcard query is used so the '*' position drives the semantics natively:
     * {@code abc*} -> startsWith, {@code *abc} -> endsWith, {@code *abc*} -> contains,
     * {@code ab*cd} -> arbitrary pattern. Otherwise an exact term query is used - every field
     * queried through here (indexedKeyValues.value included) is mapped as keyword, so match
     * would just be a case-sensitive exact match anyway; term lets it be case-insensitive
     * instead (terms/match have no such option, only term/prefix/wildcard/regexp do).
     */
    private QueryBuilder buildValueQuery(String field, Object value) {
        if (isWildcard(value)) {
            return wildcardQuery(field, (String) value).caseInsensitive(true);
        }
        return termQuery(field, value).caseInsensitive(true);
    }

    /**
     * Builds the query for one or more values on a given field, OR-ing an individual
     * (wildcard or exact) query per value - termsQuery would be more efficient for the
     * multi-value, no-wildcard case, but has no case-insensitive equivalent.
     */
    private QueryBuilder buildValuesQuery(String field, List<?> values) {
        if (values.size() == 1) {
            return buildValueQuery(field, values.get(0));
        }
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        values.forEach(value -> boolQueryBuilder.should(buildValueQuery(field, value)));
        return boolQueryBuilder;
    }

    // Same duality as OpensearchIndexer's wrapIndexedKeyValues / normalize()'s promoteIndexedKeyValues:
    // indexedKeyValues is mapped nested either at the document root (default) or under
    // indexedExtensions[] (legacy, audit_index_template_s3.json). A nested query's path has to
    // name a path the index mapping actually declares - querying "indexedKeyValues" against an
    // index that only maps "indexedExtensions.indexedKeyValues" fails the whole request
    // (query_shard_exception, not just zero hits), so this has to match whichever shape this
    // read-side is configured for, same as the writer that produced the index.
    private NestedQueryBuilder buildQueryForIndexedKeyValues(String name, List<?> values) {
        log.trace("Searching {} in [{}]", name, values);
        String nestedPath = wrapIndexedKeyValues ? "indexedExtensions.indexedKeyValues" : "indexedKeyValues";
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder
                .must(matchQuery(nestedPath + ".key", name))
                .must(buildValuesQuery(nestedPath + ".value", values));
        return nestedQuery(nestedPath, boolQueryBuilder, ScoreMode.None);
    }

    private Page runQuery(BoolQueryBuilder rootBoolQueryBuilder, String domain, String env, Integer start, Integer size) throws Exception {
        try {
            SearchSourceBuilder searchSourceBuilder =
                    new SearchSourceBuilder()
                            .query(rootBoolQueryBuilder)
                            .sort(new FieldSortBuilder("start").order(SortOrder.DESC))
                            .trackTotalHits(true)
                            .fetchSource("*", "audits")
                            .from(start)
                            .size(size);

            SearchRequest searchRequest = new SearchRequest()
                    .source(searchSourceBuilder)
                    .indices(getAuditAliasName(domain, index, env));

            SearchHits hits = client.search(searchRequest, RequestOptions.DEFAULT)
                    .getHits();

            return Page.builder()
                    .totalCount(hits.getTotalHits() == null ? 0 : hits.getTotalHits().value())
                    .size((long) hits.getHits().length)
                    .items(fromHits(hits))
                    .build();

        } catch (Exception e) {
            log.error("Failed to search opensearch results", e);
            if (e instanceof OpenSearchStatusException openSearchStatusException) {
                if (openSearchStatusException.status() == RestStatus.NOT_FOUND) {
                    return Page.builder()
                            .items(Collections.emptyList())
                            .size(0L)
                            .totalCount(0L)
                            .build();
                } else {
                    throw e;
                }
            } else {
                throw e;
            }
        }
    }

    private List<Object> fromHits(SearchHits searchHits) {
        return Stream.of(searchHits.getHits())
                .map(this::map)
                .toList();
    }

    private Object map(SearchHit searchHit) {
        return normalize(searchHit.getSourceAsMap());
    }

    // Two document shapes for indexedKeyValues coexist in the index (see OpensearchIndexer's
    // wrapIndexedKeyValues): root-level (current) and nested under indexedExtensions[] (legacy /
    // older indexed documents). Consumers (the console front-end included) only ever look at the
    // root-level field, so promote it here once rather than in every reader.
    private static final List<String> INSTANT_FIELDS = List.of("start", "end");

    private Map<String, Object> normalize(Map<String, Object> source) {
        if (source == null) {
            return source;
        }
        promoteIndexedKeyValues(source);
        normalizeInstantFields(source);
        return source;
    }

    private void promoteIndexedKeyValues(Map<String, Object> source) {
        if (source.get("indexedKeyValues") != null) {
            return;
        }
        Object indexedExtensions = source.get("indexedExtensions");
        if (!(indexedExtensions instanceof List<?> extensions)) {
            return;
        }
        for (Object extension : extensions) {
            if (extension instanceof Map<?, ?> extensionMap && extensionMap.get("indexedKeyValues") != null) {
                source.put("indexedKeyValues", extensionMap.get("indexedKeyValues"));
                return;
            }
        }
    }

    // start/end are stored as ISO-8601 strings; the console computes duration as end - start,
    // which needs epoch millis, not strings - normalize here so every consumer gets a value it
    // can do arithmetic on directly.
    private void normalizeInstantFields(Map<String, Object> source) {
        for (String field : INSTANT_FIELDS) {
            if (source.get(field) instanceof String text) {
                try {
                    source.put(field, Instant.parse(text).toEpochMilli());
                } catch (DateTimeParseException e) {
                    log.trace("Could not parse {} field as an instant: {}", field, text);
                }
            }
        }
    }

    // getAudit() only - listAudits()/searchAudits() explicitly exclude "audits" from the ES
    // fetch (fetchSource("*", "audits")) to keep paginated responses light, so there is nothing
    // to hydrate there and doing a GetObject per row would be far too slow anyway.
    private void hydrateArchivedAudits(Map<String, Object> source) {
        if (source == null || !isEmptyAudits(source.get("audits"))) {
            return;
        }
        if (!(source.get("s3Uri") instanceof String s3Uri)) {
            return;
        }
        Object audits = auditArchiveReader.readAudits(s3Uri, asLong(source.get("s3Offset")), asLong(source.get("s3Size")));
        if (audits != null) {
            source.put("audits", audits);
        }
    }

    private static boolean isEmptyAudits(Object audits) {
        return audits == null || (audits instanceof List<?> list && list.isEmpty());
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
