package org.kockpit.audit.stream.s3;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kockpit.audit.stream.api.AuditStreamJson;
import org.kockpit.audit.stream.api.model.AuditReport;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * accept() is the backpressure gate an OOM (S3 or OpenSearch falling behind ingestion, see
 * onS3Write's synchronous indexing call) needs: once buffered-but-not-yet-written bytes reach
 * maxBufferedBytes, it must block the caller - not accept-then-reject - until S3/OpenSearch drain
 * the buffer. Blocking (not returning) is what actually holds up a push-based Kinesis EFO
 * subscription's next checkpoint; an exception here would just get caught upstream and let the
 * subscription (and KCL's checkpoint ceiling) advance regardless, silently skipping whatever was
 * rejected in between.
 */
class S3AuditConsumerTest {

    private final ObjectMapper mapper = AuditStreamJson.mapper();
    private final S3Client s3Client = mock(S3Client.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private byte[] record;
    private S3AuditConsumer consumer;

    @BeforeEach
    void setUp() {
        AuditReport report = new AuditReport();
        report.setId("evt-1");
        report.setDomain("kockpit");
        report.setEnv("prod");
        report.setAppId("app");
        report.setTtl(1);
        record = mapper.writeValueAsBytes(report);

        // batchSize kept far above 1 so nothing here auto-flushes on a batch-size boundary -
        // these tests only exercise the byte-limit gate, not the batch-size one.
        consumer = new S3AuditConsumer(s3Client, "bucket", 1000, 1, List.of(1, 7), eventPublisher, new SimpleMeterRegistry(), record.length);
    }

    @Test
    @DisplayName("Un record est accepte tant que le buffer reste sous la limite d'octets")
    void accepts_records_under_the_byte_limit() {
        consumer.accept(List.of(record));

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("Une fois la limite d'octets atteinte, accept() bloque au lieu de rejeter")
    void blocks_new_records_once_the_byte_limit_is_reached() throws InterruptedException {
        consumer.accept(List.of(record));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread blockedAccept = new Thread(() -> {
            started.countDown();
            try {
                consumer.accept(List.of(record));
            } catch (S3ConsumerBackpressureException expected) {
                // Interrupted below to unwind the thread - not the behavior under test here.
            }
            finished.countDown();
        });
        blockedAccept.start();

        assertThat(started.await(2, TimeUnit.SECONDS)).as("accept() thread started").isTrue();
        // Still blocked - nothing has drained the buffer yet.
        assertThat(finished.await(300, TimeUnit.MILLISECONDS)).as("accept() returned too early").isFalse();

        blockedAccept.interrupt();
        blockedAccept.join(2000);
    }

    @Test
    @DisplayName("Un flush qui vide le buffer debloque un accept() en attente")
    void a_drain_unblocks_a_waiting_accept() throws InterruptedException {
        consumer.accept(List.of(record));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread blockedAccept = new Thread(() -> {
            started.countDown();
            consumer.accept(List.of(record));
            finished.countDown();
        });
        blockedAccept.start();

        assertThat(started.await(2, TimeUnit.SECONDS)).as("accept() thread started").isTrue();
        assertThat(finished.await(300, TimeUnit.MILLISECONDS)).as("accept() returned too early").isFalse();

        // Drains the first (only buffered) record, freeing exactly enough room for the blocked
        // second one to proceed.
        consumer.flush();
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        assertThat(finished.await(2, TimeUnit.SECONDS)).as("blocked accept() unblocked after drain").isTrue();
        blockedAccept.join(2000);
    }

    @Test
    @DisplayName("stop() ecrit tout le buffer sur S3 avant l'arret")
    void stop_writes_everything_still_buffered() {
        consumer.start();
        consumer.accept(List.of(record));

        consumer.stop();

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(consumer.isRunning()).isFalse();
    }

    @Test
    @DisplayName("drain() se termine meme si l'ecriture S3 echoue")
    void drain_terminates_when_the_s3_write_fails() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("Connection pool shut down"));
        consumer.accept(List.of(record));

        consumer.drain();

        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("Un echec apres l'ecriture S3 (indexation) n'empeche pas le drain et l'objet reste ecrit")
    void drain_terminates_when_post_write_processing_fails() {
        doThrow(new IllegalStateException("Connection pool shut down"))
                .when(eventPublisher).publishEvent(any(S3WriteEvent.class));
        consumer.accept(List.of(record));

        Logger logger = (Logger) LoggerFactory.getLogger(S3AuditConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            consumer.drain();
        } finally {
            logger.detachAppender(appender);
        }

        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(eventPublisher, times(1)).publishEvent(any(S3WriteEvent.class));
        // The object is in S3: the failure must be reported as post-write, with the written key,
        // never as an S3 write failure (which is what hid the early-closed OpenSearch client).
        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement()
                .satisfies(message -> assertThat(message)
                        .contains("post-write processing")
                        .contains("s3://bucket/")
                        .contains(".batch")
                        .doesNotContain("Failed to write"));
    }

    @Test
    @DisplayName("Le drain s'execute apres l'arret des lecteurs du stream")
    void drains_in_a_phase_after_the_stream_readers_stop() {
        // SmartLifecycle beans stop in descending phase order: the Kinesis EFO scheduler runs at
        // DEFAULT_PHASE, Kafka listener containers at DEFAULT_PHASE - 100.
        assertThat(consumer.getPhase()).isLessThan(Integer.MAX_VALUE - 100);
    }

    @Test
    @DisplayName("L'evenement d'ecriture porte la retention de l'objet S3 (ttl arrondi a une valeur autorisee)")
    void write_event_carries_the_object_retention() {
        AuditReport report = new AuditReport();
        report.setId("evt-5d");
        report.setDomain("kockpit");
        report.setEnv("prod");
        report.setAppId("app");
        report.setTtl(5);
        byte[] fiveDays = mapper.writeValueAsBytes(report);
        consumer = new S3AuditConsumer(s3Client, "bucket", 1000, 1, List.of(1, 7), eventPublisher, new SimpleMeterRegistry(), 1 << 20);

        consumer.accept(List.of(fiveDays));
        consumer.drain();

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().tagging()).isEqualTo("retention=7");
        ArgumentCaptor<S3WriteEvent> event = ArgumentCaptor.forClass(S3WriteEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getRetentionDays()).isEqualTo(7);
    }
}
