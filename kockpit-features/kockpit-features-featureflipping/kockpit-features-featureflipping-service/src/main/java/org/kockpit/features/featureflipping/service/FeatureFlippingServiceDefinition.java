package org.kockpit.features.featureflipping.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kockpit.core.sdk.ServiceDefinition;

@RequiredArgsConstructor
public class FeatureFlippingServiceDefinition implements ServiceDefinition {

    public static final String FEATURE_FLIPPING = "feature-flipping";

    /** message type of the acknowledgement an application instance publishes after applying a value */
    public static final String ACK = "ack";

    @Getter
    private final String audience;

    @Override
    public String name() {
        return FEATURE_FLIPPING;
    }

    @Override
    public String audience() {
        return audience;
    }
}
