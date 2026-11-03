package org.kockpit.communication.legacy.featureflipping;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.kockpit.communication.KeyValue;
import org.kockpit.communication.Message;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.skyscreamer.jsonassert.JSONAssert.assertEquals;
import static org.skyscreamer.jsonassert.JSONCompareMode.STRICT;

class FeatureFlippingLegacyMapperTest {

    private final FeatureFlippingLegacyMapper mapper = new FeatureFlippingLegacyMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void should_map_message_to_feature_flipping_legacy_format() {
        Message message = Message.builder()
                .id("550e8400-e29b-41d4-a716-446655440000")
                .service("feature-flipping")
                .domain("example")
                .env("prod")
                .appId("my-app")
                .keyValues(List.of(new KeyValue("my.feature.enabled", "true")))
                .build();

        FeatureFlippingLegacyMessage result = mapper.map(message);

        assertThat(result.getInternalType()).isEqualTo("BROADCAST");
        assertThat(result.getMessageId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(result.getServiceId()).isEqualTo("featureflipping");
        assertThat(result.getDomain()).isEqualTo("example");
        assertThat(result.getEnv()).isEqualTo("prod");
        assertThat(result.getApplicationId()).isEqualTo("my-app");

        FeatureFlippingPropertyUpdateRequestDto payload =
                (FeatureFlippingPropertyUpdateRequestDto) result.getMessage();
        assertThat(payload.getPropertyName()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(payload.getNewValue()).isEqualTo("true");
        assertThat(payload.getType())
                .isEqualTo("com.accor.wcp.sdk.service.featureflipping.communication.PropertyUpdateMessageRequestDto");
    }

    @Test
    @SneakyThrows
    void should_serialize_to_expected_json_format() {
        Message message = Message.builder()
                .id("550e8400-e29b-41d4-a716-446655440000")
                .service("feature-flipping")
                .domain("example")
                .env("prod")
                .appId("my-app")
                .keyValues(List.of(new KeyValue("my.feature.enabled", "true")))
                .build();

        String json = objectMapper.writeValueAsString(mapper.map(message));

        String expected = """
                {
                  "internalType": "BROADCAST",
                  "messageId": "550e8400-e29b-41d4-a716-446655440000",
                  "serviceId": "featureflipping",
                  "domain": "example",
                  "env": "prod",
                  "applicationId": "my-app",
                  "message": {
                    "__type__": "com.accor.wcp.sdk.service.featureflipping.communication.PropertyUpdateMessageRequestDto",
                    "propertyName": "550e8400-e29b-41d4-a716-446655440000",
                    "newValue": "true"
                  }
                }
                """;

        assertEquals(expected, json, STRICT);
    }
}