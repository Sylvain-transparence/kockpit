package org.kockpit.gateway;

import org.kockpit.communication.Consumer;
import org.kockpit.communication.Message;
import org.kockpit.communication.MessageJson;
import org.kockpit.communication.Publisher;
import org.kockpit.gateway.ApiKeyStore.ApiKey;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Messages between the tenant's applications and the backend (heartbeats, feature flipping,
 * dynamic configuration), over the storage the backend polls. Like audits, the key decides the
 * domain and the envs; appId, id, type and service become file names: plain names only.
 */
@Component
public class Messaging {

    private static final Duration HOUR = Duration.ofHours(1);

    // the messages' own mapper, but a message without creationDate (a primitive long, set below)
    // is accepted: Jackson 3 builds Message through its all-args constructor, absent = null
    private static final ObjectMapper MAPPER = MessageJson.mapper().rebuild()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final List<Publisher> publishers;

    private final List<Consumer> consumers;

    private final RateLimiter limiter;

    private final GatewayProperties.Messages properties;

    private final Clock clock;

    public Messaging(List<Publisher> publishers, List<Consumer> consumers, RateLimiter limiter,
                     GatewayProperties properties, Clock clock) {
        this.publishers = publishers;
        this.consumers = consumers;
        this.limiter = limiter;
        this.properties = properties.getMessages();
        this.clock = clock;
    }

    public Message publish(ApiKey key, byte[] body) {
        Message message;
        try {
            message = MAPPER.readValue(body, Message.class);
        } catch (JacksonException e) {
            throw GatewayException.badRequest("The body isn't a valid message");
        }
        if (message == null) {
            throw GatewayException.badRequest("The body isn't a valid message");
        }
        checkEnv(key, message.getEnv());
        if (message.getId() == null || message.getId().isBlank()) {
            message.setId(UUID.randomUUID().toString());
        }
        for (var field : List.of(
                new String[]{"appId", message.getAppId()},
                new String[]{"id", message.getId()},
                new String[]{"type", message.getType()})) {
            if (!Names.isValid(field[1])) {
                throw GatewayException.badRequest("%s is required: up to 100 letters, digits, '.', '_' or '-'".formatted(field[0]));
            }
        }
        if (message.getService() != null && !Names.isValid(message.getService())) {
            throw GatewayException.badRequest("service may only hold up to 100 letters, digits, '.', '_' or '-'");
        }
        if (!limiter.tryAcquire("messages:" + key.id(), properties.getPerKeyPerHour(), HOUR)) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "3600");
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "%d messages per hour per key at most".formatted(properties.getPerKeyPerHour()), headers);
        }
        message.setDomain(key.domain());
        if (message.getCreationDate() <= 0) {
            message.setCreationDate(clock.millis());
        }
        publishers.forEach(publisher -> publisher.publish(message));
        return message;
    }

    /**
     * The domain/env's messages, newest first, at most max-poll; filtered by appId, service and
     * type when given. The SDK's pollers ask for their app and service (feature-flipping,
     * dyna-config): the backend's messages of those services carry no type.
     */
    public List<Message> poll(ApiKey key, String env, String appId, String service, String type) {
        checkEnv(key, env);
        for (var filter : List.of(new String[]{"appId", appId}, new String[]{"service", service}, new String[]{"type", type})) {
            if (filter[1] != null && !Names.isValid(filter[1])) {
                throw GatewayException.badRequest("Invalid " + filter[0]);
            }
        }
        return consumers.stream()
                .flatMap(consumer -> consumer.poll(key.domain(), env, null, service).stream())
                .filter(Objects::nonNull)
                .filter(message -> appId == null || appId.equals(message.getAppId()))
                .filter(message -> service == null || service.equals(message.getService()))
                .filter(message -> type == null || type.equals(message.getType()))
                .sorted(Message.BY_CREATION_DATE.reversed())
                .limit(properties.getMaxPoll())
                .toList();
    }

    private static void checkEnv(ApiKey key, String env) {
        if (env == null || !key.envs().contains(env)) {
            throw GatewayException.badRequest("env %s isn't one of the tenant's envs %s".formatted(env, new TreeSet<>(key.envs())));
        }
    }
}
