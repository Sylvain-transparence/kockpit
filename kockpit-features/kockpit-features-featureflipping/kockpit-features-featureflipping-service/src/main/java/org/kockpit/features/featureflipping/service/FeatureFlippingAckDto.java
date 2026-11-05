package org.kockpit.features.featureflipping.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of the "ack" message an application instance publishes once it has applied a flag value.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlippingAckDto {

    private String instanceId;

    private String key;

    private Boolean enabled;

    /** creationDate of the value message that was applied */
    private Long valueCreationDate;
}
