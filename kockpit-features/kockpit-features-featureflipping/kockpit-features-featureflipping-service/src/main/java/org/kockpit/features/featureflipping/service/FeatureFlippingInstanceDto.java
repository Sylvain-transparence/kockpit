package org.kockpit.features.featureflipping.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Synchronization state of one application instance for one flag, as served to the console.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlippingInstanceDto {

    public enum Status {
        /** acknowledged the current value */
        IN_SYNC,
        /** acknowledged an older value */
        OUTDATED,
        /** alive (heartbeat) but no acknowledgement yet */
        WAITING
    }

    private String instanceId;

    private Status status;

    /** value the instance acknowledged */
    private Boolean enabled;

    private Long valueCreationDate;

    /** when the instance acknowledged */
    private Long ackDate;
}
