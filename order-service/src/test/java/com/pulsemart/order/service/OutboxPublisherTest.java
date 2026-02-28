package com.pulsemart.order.service;

import com.pulsemart.order.domain.OutboxEvent;
import com.pulsemart.order.domain.OutboxStatus;
import com.pulsemart.order.repository.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private MeterRegistry meterRegistry;
    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        outboxPublisher = new OutboxPublisher(outboxRepository, kafkaTemplate, meterRegistry);
        // register gauge so @PostConstruct side-effect is covered
        outboxPublisher.registerMetrics();
    }

    @Test
    void publishPendingEvents_shouldSkipWhenNoPendingEvents() {
        when(outboxRepository.findPendingEventsForUpdate()).thenReturn(Collections.emptyList());

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void publishPendingEvents_shouldMarkEventAsSentAfterSuccessfulKafkaSend() {
        OutboxEvent event = pendingEvent("ORDER_CREATED");
        when(outboxRepository.findPendingEventsForUpdate()).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(captor.getValue().getSentAt()).isNotNull();
    }

    @Test
    void publishPendingEvents_shouldIncrementRetryCountOnKafkaFailure() {
        OutboxEvent event = pendingEvent("ORDER_CREATED");
        when(outboxRepository.findPendingEventsForUpdate()).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        // Status remains PENDING after 1 failure (not FAILED yet)
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getRetryCount()).isEqualTo(1);
    }

    @Test
    void publishPendingEvents_shouldMarkAsFailedAfterMaxRetries() {
        OutboxEvent event = pendingEvent("ORDER_CREATED");
        event.setRetryCount(4); // one more failure will hit MAX_RETRIES (5)
        when(outboxRepository.findPendingEventsForUpdate()).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(captor.getValue().getRetryCount()).isEqualTo(5);
    }

    @Test
    void publishPendingEvents_shouldPublishToCorrectTopic() {
        OutboxEvent orderCreated = pendingEvent("ORDER_CREATED");
        OutboxEvent paymentInitiated = pendingEvent("PAYMENT_INITIATED");
        when(outboxRepository.findPendingEventsForUpdate())
                .thenReturn(List.of(orderCreated, paymentInitiated));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate, times(2)).send(eq("order.events"), anyString(), anyString());
    }

    @Test
    void publishPendingEvents_shouldProcessMultipleEventsIndependently() {
        OutboxEvent good = pendingEvent("ORDER_CREATED");
        OutboxEvent bad = pendingEvent("ORDER_CANCELLED");
        when(outboxRepository.findPendingEventsForUpdate()).thenReturn(List.of(good, bad));
        // First send succeeds, second fails
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(2)).save(captor.capture());
        List<OutboxEvent> saved = captor.getAllValues();
        assertThat(saved.get(0).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(saved.get(1).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.get(1).getRetryCount()).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OutboxEvent pendingEvent(String eventType) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .aggregateId(UUID.randomUUID())
                .payload("{\"eventType\":\"" + eventType + "\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }
}
