package org.kockpit.features.featureflipping.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlippingDto {

    private String domain;

    private String env;

    private String appId;

    private String key;

    private Date expirationDate;

    private Boolean enabled;

    private String comment;

    /** creationDate of the message that carries the current value */
    private Long updatedAt;

    /** instances of the application and whether they applied the current value (served by the backend) */
    private List<FeatureFlippingInstanceDto> instances;
}
