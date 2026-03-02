package com.pulsemart.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulsemart.payment.domain.OutboxEvent;
import com.pulsemart.payment.domain.OutboxStatus;
import com.pulsemart.payment.domain.Payment;
import com.pulsemart.payment.domain.PaymentStatus;
import com.pulsemart.payment.repository.OutboxRepository;
import com.pulsemart.payment.repository.PaymentRepository;
import com.pulsemart.shared.event.EventType;
import com.pulsemart.shared.event.payload.PaymentInitiatedPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        meterRegistry = new SimpleMeterRegistry();

        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(span.tag(anyString(), anyString())).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(new Tracer.SpanInScope() {
            @Override public void close() {}
        });

        paymentService = new PaymentService(paymentRepository, outboxRepository, objectMapper, meterRegistry, tracer);
    }

    // ── success path ──────────────────────────────────────────────────────────

    @Test
    void processPayment_shouldSavePaymentWithSucceededStatus() {
        ReflectionTestUtils.setField(paymentService, "failureRate", 0.0); // force success
        PaymentInitiatedPayload payload = payload();
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        paymentService.processPayment(payload);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(captor.getValue().getFailureReason()).isNull();
        assertThat(captor.getValue().getOrderId()).isEqualTo(payload.getOrderId());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo(payload.getAmount());
    }

    @Test
    void processPayment_shouldEmitPaymentSucceededOutboxEvent() {
        ReflectionTestUtils.setField(paymentService, "failureRate", 0.0);
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        paymentService.processPayment(payload());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getEventType()).isEqualTo(EventType.PAYMENT_SUCCEEDED.name());
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getPayload()).contains("PAYMENT_SUCCEEDED");
    }

    @Test
    void processPayment_shouldIncrementSucceededCounter() {
        ReflectionTestUtils.setField(paymentService, "failureRate", 0.0);
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        paymentService.processPayment(payload());

        assertThat(meterRegistry.counter("pulsemart.payment.total", "outcome", "succeeded").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("pulsemart.payment.total", "outcome", "failed").count())
                .isEqualTo(0.0);
    }

    // ── failure path ─────────────────────────────────────────────────────────

    @Test
    void processPayment_shouldSavePaymentWithFailedStatus() {
        ReflectionTestUtils.setField(paymentService, "failureRate", 1.0); // force failure
        PaymentInitiatedPayload payload = payload();
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        paymentService.processPayment(payload);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(captor.getValue().getFailureReason()).isEqualTo("Simulated payment failure");
    }

    @Test
    void processPayment_shouldEmitPaymentFailedOutboxEvent() {
        ReflectionTestUtils.setField(paymentService, "failureRate", 1.0);
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        paymentService.processPayment(payload());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getEventType()).isEqualTo(EventType.PAYMENT_FAILED.name());
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getPayload()).contains("PAYMENT_FAILED");
    }

    @Test
    void processPayment_shouldIncrementFailedCounter() {
        ReflectionTestUtils.setField(paymentService, "failureRate", 1.0);
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        paymentService.processPayment(payload());

        assertThat(meterRegistry.counter("pulsemart.payment.total", "outcome", "failed").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("pulsemart.payment.total", "outcome", "succeeded").count())
                .isEqualTo(0.0);
    }

    @Test
    void processPayment_failedPayload_shouldContainReservationId() {
        ReflectionTestUtils.setField(paymentService, "failureRate", 1.0);
        PaymentInitiatedPayload payload = payload();
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        paymentService.processPayment(payload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getPayload())
                .contains(payload.getReservationId().toString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PaymentInitiatedPayload payload() {
        return PaymentInitiatedPayload.builder()
                .orderId(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .reservationId(UUID.randomUUID())
                .amount(new BigDecimal("49.99"))
                .build();
    }
}
