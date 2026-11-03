package org.kockpit.audit.notification.eventhub;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import org.junit.jupiter.api.Test;
import org.kockpit.audit.api.AuditReportWrapper;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventHubAuditReportNotificationServiceTest {

    private final EventHubProducerClient producerClient = mock(EventHubProducerClient.class);
    private final EventHubAuditReportNotificationService service = new EventHubAuditReportNotificationService(producerClient);

    @Test
    void one_batch_per_domain_env_app_keyed_like_the_kafka_notifier() {
        List<FakeBatch> batches = batches(10);

        service.notify(List.of(report("offers", "dev", "offers"), report("orders", "pro", "order"), report("offers", "dev", "offers")));

        ArgumentCaptor<CreateBatchOptions> options = ArgumentCaptor.forClass(CreateBatchOptions.class);
        verify(producerClient, times(2)).createBatch(options.capture());
        assertThat(options.getAllValues()).extracting(CreateBatchOptions::getPartitionKey)
                .containsExactly("offers-dev-offers", "orders-pro-order");
        assertThat(batches).extracting(b -> b.events.size()).containsExactly(2, 1);
        verify(producerClient, times(2)).send(any(EventDataBatch.class));
    }

    @Test
    void a_full_batch_is_sent_and_the_next_report_starts_a_new_one() {
        List<FakeBatch> batches = batches(2);

        service.notify(List.of(report("d", "e", "a"), report("d", "e", "a"), report("d", "e", "a")));

        assertThat(batches).extracting(b -> b.events.size()).containsExactly(2, 1);
        verify(producerClient, times(2)).send(any(EventDataBatch.class));
    }

    @Test
    void a_report_too_large_for_an_empty_batch_is_dropped_without_blocking_the_others() {
        List<FakeBatch> batches = batches(1);
        AuditReportWrapper tooLarge = report("d", "e", "a", new byte[FakeBatch.MAX_SIZE + 1]);

        service.notify(List.of(report("d", "e", "a"), tooLarge, report("d", "e", "a")));

        // The empty batch opened for the dropped report takes the next one.
        assertThat(batches).extracting(b -> b.events.size()).containsExactly(1, 1);
        verify(producerClient, times(2)).send(any(EventDataBatch.class));
    }

    @Test
    void nothing_is_sent_without_reports() {
        service.notify(List.of());

        verify(producerClient, never()).createBatch(any(CreateBatchOptions.class));
        verify(producerClient, never()).send(any(EventDataBatch.class));
    }

    @Test
    void a_send_failure_is_logged_not_thrown() {
        batches(10);
        doThrow(new RuntimeException("hub unavailable")).when(producerClient).send(any(EventDataBatch.class));

        assertThatCode(() -> service.notify(List.of(report("offers", "dev", "offers"), report("orders", "pro", "order"))))
                .doesNotThrowAnyException();
        // A failing key does not prevent the others from being tried.
        verify(producerClient, times(2)).send(any(EventDataBatch.class));
    }

    private List<FakeBatch> batches(int maxEvents) {
        List<FakeBatch> created = new ArrayList<>();
        when(producerClient.createBatch(any(CreateBatchOptions.class))).thenAnswer(invocation -> {
            FakeBatch fake = new FakeBatch(maxEvents);
            created.add(fake);
            return fake.mock;
        });
        return created;
    }

    private static AuditReportWrapper report(String domain, String env, String appId) {
        return report(domain, env, appId, "{}".getBytes());
    }

    private static AuditReportWrapper report(String domain, String env, String appId, byte[] data) {
        return new AuditReportWrapper(domain, env, appId, "artifact", "id", data);
    }

    /** EventDataBatch has no public constructor: a mock that accepts up to maxEvents events of at most MAX_SIZE bytes. */
    private static final class FakeBatch {
        static final int MAX_SIZE = 1024;
        final List<EventData> events = new ArrayList<>();
        final EventDataBatch mock = mock(EventDataBatch.class);

        FakeBatch(int maxEvents) {
            when(mock.tryAdd(any(EventData.class))).thenAnswer(invocation -> {
                EventData event = invocation.getArgument(0);
                if (events.size() >= maxEvents || event.getBody().length > MAX_SIZE) {
                    return false;
                }
                return events.add(event);
            });
            when(mock.getCount()).thenAnswer(invocation -> events.size());
            when(mock.getMaxSizeInBytes()).thenReturn(MAX_SIZE);
        }
    }
}
