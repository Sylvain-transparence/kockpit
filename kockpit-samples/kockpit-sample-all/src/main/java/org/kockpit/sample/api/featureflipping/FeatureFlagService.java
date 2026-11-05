package org.kockpit.sample.api.featureflipping;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kockpit.communication.Message;
import org.kockpit.core.sdk.OnMessageListener;
import org.kockpit.features.featureflipping.service.FeatureFlippingDto;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.kockpit.features.featureflipping.service.FeatureFlippingServiceDefinition.FEATURE_FLIPPING;

@Component
@RequiredArgsConstructor
public class FeatureFlagService implements OnMessageListener {

    @Getter
    private final Map<String, Boolean> keys = new HashMap<>();

    // Mapper auto-configure par Boot : simple convertValue en memoire, aucun contrat de format.
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message) {
        if (!message.getService().equals(FEATURE_FLIPPING)) {
            return;
        }
        if (message.getBody() != null) {
            FeatureFlippingDto featureFlippingDto = objectMapper
                    .convertValue(message.getBody(), FeatureFlippingDto.class);
            keys.put(featureFlippingDto.getKey(), featureFlippingDto.getEnabled());
        } else if (message.getKeyValues() != null) {
            // messages written before the backend added the body only carry key/value
            message.getKeyValues().forEach(kv -> keys.put(kv.key(), Boolean.valueOf(String.valueOf(kv.value()))));
        }
    }

    Boolean get(String key) {
        return keys.getOrDefault(key, false);
    }
}
