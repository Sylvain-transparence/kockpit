package org.kockpit.kinesis.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KinesisRecord {
    private String messageId;
    private String domain;
    private String env;
    private String applicationId;
    private String serviceId;
    private Object message;
    private Map<String, Object> propertyValues;
    // legacy heartbeat fields
    private String applicationInstance;
    private Boolean ended;
}