package com.pulsemart.inventory.service;

import com.pulsemart.inventory.domain.OutboxEvent;
import com.pulsemart.inventory.domain.OutboxStatus;
import com.pulsemart.inventory.repository.OutboxRepository;
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
        OutboxEvent event = pendingEvent("INVENTORY_RESERVED");
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
        OutboxEvent event = pendingEvent("INVENTORY_RESERVED");
        when(outboxRepository.findPendingEventsForUpdate()).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));

        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(captor.getValue().getRetryCount()).isEqualTo(1);
    }

    @Test
    void publishPendingEvents_shouldMarkAsFailedAfterMaxRetries() {
        OutboxEvent event = pendingEvent("INVENTORY_FAILED");
        event.setRetryCount(4); // next failure hits MAX_RETRIES (5)
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
    void publishPendingEvents_shouldPublishToInventoryEventsTopic() {
        OutboxEvent reserved = pendingEvent("INVENTORY_RESERVED");
        OutboxEvent failed = pendingEvent("INVENTORY_FAILED");
        OutboxEvent released = pendingEvent("INVENTORY_RELEASED");
        when(outboxRepository.findPendingEventsForUpdate())
                .thenReturn(List.of(reserved, failed, released));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate, times(3)).send(eq("inventory.events"), anyString(), anyString());
    }

    @Test
    void publishPendingEvents_shouldProcessEventsIndependently() {
        OutboxEvent good = pendingEvent("INVENTORY_RESERVED");
        OutboxEvent bad = pendingEvent("INVENTORY_FAILED");
        when(outboxRepository.findPendingEventsForUpdate()).thenReturn(List.of(good, bad));
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
