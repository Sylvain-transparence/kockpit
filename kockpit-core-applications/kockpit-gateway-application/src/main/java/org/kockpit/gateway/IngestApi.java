package org.kockpit.gateway;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.kockpit.communication.Message;
import org.kockpit.gateway.ApiKeyStore.ApiKey;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * <ul>
 *   <li>{@code POST /ingest/v1/audits}: an audit or an array of audits (optionally
 *       {@code Content-Encoding: gzip}) - 202 {@code {accepted, remaining, quota}}; 429 past the
 *       daily quota, with {@code Retry-After} (seconds to midnight UTC);</li>
 *   <li>{@code POST /ingest/v1/messages}: one message - 202;</li>
 *   <li>{@code GET /ingest/v1/messages?env=&appId=&service=&type=}: the env's messages, newest
 *       first, filtered by the optional appId, service and type.</li>
 * </ul>
 * Audit responses carry {@code X-Quota-Limit} and {@code X-Quota-Remaining}.
 */
@RestController
@RequestMapping("/ingest/v1")
@RequiredArgsConstructor
public class IngestApi {

    public static final String QUOTA_LIMIT = "X-Quota-Limit";

    public static final String QUOTA_REMAINING = "X-Quota-Remaining";

    private final AuditIngestion audits;

    private final Messaging messaging;

    private final GatewayProperties properties;

    @PostMapping("/audits")
    ResponseEntity<AuditIngestion.Accepted> audits(@RequestAttribute(ApiKeyFilter.ATTRIBUTE) ApiKey key,
                                                   HttpServletRequest request) throws IOException {
        AuditIngestion.Accepted accepted = audits.ingest(key, Bodies.read(request, properties.getAudits().getMaxBodyBytes()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(QUOTA_LIMIT, String.valueOf(accepted.quota()))
                .header(QUOTA_REMAINING, String.valueOf(accepted.remaining()))
                .body(accepted);
    }

    @PostMapping("/messages")
    ResponseEntity<Map<String, String>> publish(@RequestAttribute(ApiKeyFilter.ATTRIBUTE) ApiKey key,
                                                HttpServletRequest request) throws IOException {
        Message message = messaging.publish(key, Bodies.read(request, properties.getMessages().getMaxBodyBytes()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", message.getId()));
    }

    @GetMapping("/messages")
    List<Message> poll(@RequestAttribute(ApiKeyFilter.ATTRIBUTE) ApiKey key,
                       @RequestParam String env,
                       @RequestParam(required = false) String appId,
                       @RequestParam(required = false) String service,
                       @RequestParam(required = false) String type) {
        return messaging.poll(key, env, appId, service, type);
    }

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<Map<String, String>> refused(GatewayException e) {
        return ResponseEntity.status(e.getStatus()).headers(e.getHeaders())
                .body(Map.of("error", e.getError(), "message", e.getMessage()));
    }
}
