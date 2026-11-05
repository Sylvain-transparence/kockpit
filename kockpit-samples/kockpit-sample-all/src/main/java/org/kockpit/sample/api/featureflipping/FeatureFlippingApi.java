package org.kockpit.sample.api.featureflipping;

import lombok.RequiredArgsConstructor;
import org.kockpit.features.featureflipping.application.FeatureFlippingClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/feature-flipping")
@RequiredArgsConstructor
public class FeatureFlippingApi {

    private final FeatureFlippingClient featureFlippingClient;

    @GetMapping("keys/{key}")
    Map<String, Object> evaluate(@PathVariable String key) {
        return Map.of(key, featureFlippingClient.isEnabled(key));
    }

    @GetMapping("keys")
    Map<String, Boolean> keys() {
        return featureFlippingClient.getValues();
    }
}
