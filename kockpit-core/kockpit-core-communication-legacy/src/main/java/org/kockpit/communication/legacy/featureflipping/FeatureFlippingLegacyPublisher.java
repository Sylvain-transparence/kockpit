package org.kockpit.communication.legacy.featureflipping;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.kockpit.communication.Message;
import org.kockpit.communication.Publisher;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;

@RequiredArgsConstructor
public class FeatureFlippingLegacyPublisher implements Publisher {

    static final String FEATURE_FLIPPING_SERVICE = "feature-flipping";

    private final S3Client s3Client;
    private final String bucketName;
    private final ObjectMapper objectMapper;

    private final FeatureFlippingLegacyMapper mapper = new FeatureFlippingLegacyMapper();

    @SneakyThrows
    @Override
    public void publish(Message message) {
        // acknowledgements ("ack") from the application instances have no legacy equivalent
        if (!FEATURE_FLIPPING_SERVICE.equals(message.getService()) || "ack".equals(message.getType())) {
            return;
        }

        write(mapper.map(message), message);
    }

    @SneakyThrows
    private void write(FeatureFlippingLegacyMessage legacyMessage, Message message) {
        String path = formatPath(message.getDomain(), message.getEnv(), message.getAppId());
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            objectMapper.writeValue(os, legacyMessage);
            byte[] jsonBytes = os.toByteArray();

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key("%s/%s.json".formatted(path, message.getId()))
                    .contentType("application/json")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(jsonBytes));
        }
    }

    static String formatPath(String domain, String env, String appId) {
        return "%s/%s/%s".formatted(domain, env, appId);
    }
}