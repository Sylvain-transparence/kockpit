package org.kockpit.communication.legacy.dynaconfig;

import lombok.SneakyThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kockpit.communication.KeyValue;
import org.kockpit.communication.Message;
import org.kockpit.communication.legacy.LegacyJson;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DynaConfigLegacyMapperTest {

    DynaConfigLegacyMapper dynaConfigLegacyMapper = new DynaConfigLegacyMapper();

    @Test
    @SneakyThrows
    @DisplayName("On legacy S3 message with __type__ fields, should map to Message with key-values")
    void toMessage_from_legacy_broadcast() {
        String json = """
                {
                  "internalType": "BROADCAST",
                  "messageId": "d12fc6dd-2a7e-4c89-9137-3b3ea5084811",
                  "serviceId": "dynaconfig",
                  "domain": "platform",
                  "env": "dev",
                  "applicationId": "samples",
                  "message": {
                    "__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.InstanceInitPropertiesUpdateRequestDto",
                    "updates": [
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "dyna.property.backend.call.timeout", "newValue": "1"},
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "application.client.axa.basepath", "newValue": null},
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "application.client.apim.aps.timeout", "newValue": "1012"},
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "application.client.insurancequotation.timeout", "newValue": null},
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "application.client.apim.aps.x-custom-header", "newValue": "custom2"},
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "application.samples.testing.display.for.a.long.property.name.hello-this-is-a-long-property-name-to-test-if-it-is-correctly-displayed", "newValue": null},
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "ff.backend.new2022", "newValue": "true"},
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "sample.resttemplate.timeout", "newValue": "5001"},
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "application.client.axa.timeout", "newValue": "7000"},
                      {"__type__": "com.accor.wcp.sdk.service.dynaconfig.communication.PropertyUpdateMessageRequestDto", "propertyName": "ff.backend.test.nullvalue", "newValue": "default from legacy"}
                    ]
                  }
                }
                """;

        ObjectMapper objectMapper = LegacyJson.mapper();
        DynaConfigLegacyMessage legacy = objectMapper.readValue(json, DynaConfigLegacyMessage.class);

        Message message = dynaConfigLegacyMapper.toMessage(legacy);

        assertThat(message.getId()).isEqualTo("d12fc6dd-2a7e-4c89-9137-3b3ea5084811");
        assertThat(message.getService()).isEqualTo("dynaconfig");
        assertThat(message.getType()).isEqualTo("update");
        assertThat(message.getDomain()).isEqualTo("platform");
        assertThat(message.getEnv()).isEqualTo("dev");
        assertThat(message.getAppId()).isEqualTo("samples");
        // Verrou d'unite : creationDate est en millisecondes epoch. Une date en secondes
        // s'ecarterait de ~1,79e12 de l'horloge courante et serait vue comme datant de 1970.
        assertThat(message.getCreationDate())
                .isCloseTo(System.currentTimeMillis(), within(60_000L));
        assertThat(message.getKeyValues()).containsExactly(
                new KeyValue("dyna.property.backend.call.timeout", "1"),
                new KeyValue("application.client.axa.basepath", null),
                new KeyValue("application.client.apim.aps.timeout", "1012"),
                new KeyValue("application.client.insurancequotation.timeout", null),
                new KeyValue("application.client.apim.aps.x-custom-header", "custom2"),
                new KeyValue("application.samples.testing.display.for.a.long.property.name.hello-this-is-a-long-property-name-to-test-if-it-is-correctly-displayed", null),
                new KeyValue("ff.backend.new2022", "true"),
                new KeyValue("sample.resttemplate.timeout", "5001"),
                new KeyValue("application.client.axa.timeout", "7000"),
                new KeyValue("ff.backend.test.nullvalue", "default from legacy")
        );
    }

    @SneakyThrows
    private String toJson(DynaConfigLegacyMessage mapped) {
        return new ObjectMapper().writeValueAsString(mapped);
    }
}
