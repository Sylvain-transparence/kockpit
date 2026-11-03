package org.kockpit.features.manifest.services;

import org.junit.jupiter.api.Test;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.kockpit.features.manifest.services.dto.ServiceDto;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestReaderTest {

    @Test
    void should_parse_current_format_manifest_as_is() {
        String json = """
                {
                  "domain": "orders",
                  "env": "dev",
                  "policies": [
                    { "groups": ["team"], "permissions": ["*"], "resources": ["audit:*"] }
                  ],
                  "services": [
                    { "type": "audit", "name": "orders-api-v1", "id": "orders-api-v1", "config": { "label": "Order - API v1" } }
                  ]
                }
                """;

        ManifestDto manifest = ManifestReader.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.getDomain()).isEqualTo("orders");
        assertThat(manifest.getEnv()).isEqualTo("dev");
        assertThat(manifest.getPolicies()).hasSize(1);
        assertThat(manifest.getServices()).hasSize(1);

        ServiceDto service = manifest.getServices().getFirst();
        assertThat(service.getType()).isEqualTo("audit");
        assertThat(service.getName()).isEqualTo("orders-api-v1");
        assertThat(service.getId()).isEqualTo("orders-api-v1");
    }

    @Test
    void should_convert_legacy_applications_audit_services_to_service_dtos() {
        String json = """
                {
                  "domain": "orders",
                  "env": "dev",
                  "policies": [
                    { "groups": ["team"], "permissions": [], "resources": ["audit:*"] }
                  ],
                  "applications": [
                    {
                      "id": "orders-service",
                      "label": "ORDERS Order",
                      "subDomain": "Domain",
                      "services": {
                        "audit": [
                          {
                            "name": "orders-api-v1",
                            "label": "Order - API v1",
                            "appIds": ["orders-service"],
                            "resultColumns": [
                              { "renderer": "date", "name": "start", "label": "Start date" },
                              { "renderer": "substring", "name": "traceId", "label": "Trace ID" },
                              { "renderer": null, "name": "httpMethod", "label": "Method" },
                              { "renderer": null, "name": "requestUri", "label": "Url" },
                              { "renderer": "status", "name": "httpStatus", "label": "Status" }
                            ],
                            "searchMetadatas": [
                              { "subtype": null, "name": "start", "description": "Start date", "label": "Start date", "type": "Date" },
                              { "subtype": "builtin.kv", "name": "httpMethod", "description": "Method", "label": "HTTP Method", "type": "string" },
                              { "subtype": "builtin.kv", "name": "httpStatus", "description": "Status", "label": "Status", "type": "integer" }
                            ]
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        ManifestDto manifest = ManifestReader.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.getDomain()).isEqualTo("orders");
        assertThat(manifest.getEnv()).isEqualTo("dev");
        assertThat(manifest.getPolicies()).hasSize(1);
        assertThat(manifest.getServices()).hasSize(1);

        ServiceDto service = manifest.getServices().getFirst();
        assertThat(service.getType()).isEqualTo("audit");
        assertThat(service.getName()).isEqualTo("orders-api-v1");
        assertThat(service.getId()).isEqualTo("orders-api-v1");

        assertThat(service.getConfig()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) service.getConfig();

        // httpMethod/requestUri/httpStatus are renamed to the literal keys AuditListPage
        // special-cases (method/path/status) for badge and truncated-path rendering; search_columns
        // still targets the real indexed field names below.
        assertThat(config.get("columns")).isEqualTo(List.of("start", "traceId", "method", "path", "status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> searchColumns = (List<Map<String, Object>>) config.get("search_columns");
        assertThat(searchColumns).containsExactly(
                Map.of("name", "start", "type", "date", "path", "start"),
                Map.of("name", "httpMethod", "type", "text", "path", "indexedKeyValues.httpMethod"),
                Map.of("name", "httpStatus", "type", "number", "path", "indexedKeyValues.httpStatus")
        );
        assertThat(config.get("app_ids")).isEqualTo(List.of("orders-service"));
    }

    @Test
    void should_not_set_app_ids_when_the_legacy_audit_service_declares_none() {
        String json = """
                {
                  "domain": "orders", "env": "dev",
                  "applications": [
                    { "id": "orders-service",
                      "services": { "audit": [ { "name": "orders-api-v1", "appIds": [] } ] } }
                  ]
                }
                """;

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) ManifestReader.read(json.getBytes(StandardCharsets.UTF_8))
                .getServices().getFirst().getConfig();

        assertThat(config).doesNotContainKey("app_ids");
    }

    @Test
    void should_convert_every_application_and_every_audit_entry() {
        String json = """
                {
                  "domain": "orders",
                  "env": "dev",
                  "applications": [
                    { "id": "app-1", "services": { "audit": [ { "name": "audit-1" }, { "name": "audit-2" } ] } },
                    { "id": "app-2", "services": { "audit": [ { "name": "audit-3" } ] } }
                  ]
                }
                """;

        ManifestDto manifest = ManifestReader.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.getServices())
                .extracting(ServiceDto::getName)
                .containsExactly("audit-1", "audit-2", "audit-3");
    }

    @Test
    void should_ignore_legacy_service_types_other_than_audit() {
        String json = """
                {
                  "domain": "orders",
                  "env": "dev",
                  "applications": [
                    {
                      "id": "orders-service",
                      "services": {
                        "cache": [ { "name": "someCache" } ],
                        "dynaconfig": [ { "label": "Order - API v1" } ]
                      }
                    }
                  ]
                }
                """;

        ManifestDto manifest = ManifestReader.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.getServices()).isEmpty();
    }

    @Test
    void should_ignore_applications_key_when_services_are_already_present() {
        String json = """
                {
                  "domain": "orders",
                  "env": "dev",
                  "services": [
                    { "type": "audit", "name": "current-format-service", "id": "current-format-service" }
                  ],
                  "applications": [
                    { "id": "orders-service", "services": { "audit": [ { "name": "legacy-should-be-ignored" } ] } }
                  ]
                }
                """;

        ManifestDto manifest = ManifestReader.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.getServices())
                .extracting(ServiceDto::getName)
                .containsExactly("current-format-service");
    }

    @Test
    void should_read_the_same_manifest_from_an_input_stream_as_from_bytes() {
        String json = """
                {
                  "domain": "orders",
                  "env": "dev",
                  "applications": [
                    { "id": "orders-service", "services": { "audit": [ { "name": "orders-api-v1" } ] } }
                  ]
                }
                """;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        ManifestDto fromBytes = ManifestReader.read(bytes);
        ManifestDto fromStream = ManifestReader.read(new ByteArrayInputStream(bytes));

        assertThat(fromStream.getDomain()).isEqualTo(fromBytes.getDomain());
        assertThat(fromStream.getServices())
                .extracting(ServiceDto::getName)
                .containsExactlyElementsOf(fromBytes.getServices().stream().map(ServiceDto::getName).toList());
    }
}
