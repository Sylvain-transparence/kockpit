package org.kockpit.communication.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kockpit.communication.Message;
import org.kockpit.gateway.client.GatewayClient;
import org.kockpit.gateway.client.SendGate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTransportTest {

    private static final String KEY = "kpk_test";

    private HttpServer server;

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private volatile int status = 202;

    private volatile String answer = "{\"id\":\"x\"}";

    private GatewayClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("%s %s key=%s %s".formatted(exchange.getRequestMethod(), exchange.getRequestURI(),
                    exchange.getRequestHeaders().getFirst(GatewayClient.API_KEY_HEADER), body));
            byte[] response = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 429) {
                exchange.getResponseHeaders().add("Retry-After", "60");
            }
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        client = new GatewayClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", KEY,
                Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void a_message_is_posted_with_the_key() {
        new HttpPublisher(client, new SendGate("messages"), Duration.ofMinutes(5)).publish(heartbeat());

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request).startsWith("POST /ingest/v1/messages key=kpk_test ");
            assertThat(request).contains("\"service\":\"heartbeat\"", "\"appId\":\"shop\"", "\"env\":\"dev\"");
        });
    }

    @Test
    void over_the_limit_publishing_pauses() {
        status = 429;
        answer = "{\"error\":\"rate_limited\",\"message\":\"1200 messages per hour per key at most\"}";
        SendGate gate = new SendGate("messages");
        HttpPublisher publisher = new HttpPublisher(client, gate, Duration.ofMinutes(5));

        publisher.publish(heartbeat());
        publisher.publish(heartbeat());
        publisher.publish(heartbeat());

        assertThat(requests).hasSize(1);
        assertThat(gate.dropped()).isEqualTo(2);
    }

    @Test
    void an_unreachable_gateway_never_throws() {
        server.stop(0);
        new HttpPublisher(client, new SendGate("messages"), Duration.ofMinutes(5)).publish(heartbeat());
        assertThat(new HttpConsumer(client, new SendGate("messages"), Duration.ofMinutes(5)).poll("ignored", "dev", "shop", "featureflipping")).isEmpty();
    }

    @Test
    void polling_asks_for_the_app_and_service_and_returns_oldest_first() {
        status = 200;
        answer = """
                [{"id":"new","service":"featureflipping","appId":"shop","env":"dev","creationDate":2000},
                 {"id":"old","service":"featureflipping","appId":"shop","env":"dev","creationDate":1000,"extra":"ignored"}]""";

        List<Message> messages = new HttpConsumer(client, new SendGate("messages"), Duration.ofMinutes(5)).poll("whatever", "dev", "shop", "featureflipping");

        assertThat(requests).singleElement().asString()
                .startsWith("GET /ingest/v1/messages?env=dev&appId=shop&service=featureflipping key=kpk_test");
        assertThat(messages).extracting(Message::getId).containsExactly("old", "new");
    }

    @Test
    void a_refused_poll_returns_nothing() {
        status = 400;
        answer = "{\"error\":\"bad_request\",\"message\":\"env local isn't one of the tenant's envs [dev]\"}";
        assertThat(new HttpConsumer(client, new SendGate("messages"), Duration.ofMinutes(5)).poll("d", "local", "shop", "dynaconfig")).isEmpty();
    }

    @Test
    void a_rejected_key_stops_polling_too() {
        status = 401;
        answer = "{\"error\":\"invalid_api_key\",\"message\":\"Unknown or revoked API key\"}";
        SendGate gate = new SendGate("messages");
        HttpConsumer consumer = new HttpConsumer(client, gate, Duration.ofMinutes(5));

        consumer.poll("d", "dev", "shop", "dynaconfig");
        consumer.poll("d", "dev", "shop", "feature-flipping");
        new HttpPublisher(client, gate, Duration.ofMinutes(5)).publish(heartbeat());

        assertThat(requests).hasSize(1);
        assertThat(gate.isOpen()).isFalse();
    }

    private static Message heartbeat() {
        return Message.builder().id("i-1").service("heartbeat").type("heartbeat")
                .domain("kockpit").env("dev").appId("shop").creationDate(System.currentTimeMillis()).build();
    }
}
