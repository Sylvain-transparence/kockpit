package org.kockpit.audit.stream.s3;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

public class S3WriteEvent extends ApplicationEvent {

    @Getter
    private final String s3Key;

    @Getter
    private final String s3BucketName;

    @Getter
    private final List<S3Record> batch;

    // The object's "retention=<days>" tag: how long S3 keeps it. Anything indexed with a pointer
    // into this object must not outlive it, or its s3Uri ends up pointing at a deleted object.
    @Getter
    private final int retentionDays;

    public S3WriteEvent(Object source, String s3Key, List<S3Record> batch, String s3BucketName, int retentionDays) {
        super(source);
        this.s3Key = s3Key;
        this.batch = batch;
        this.s3BucketName = s3BucketName;
        this.retentionDays = retentionDays;
    }
}
