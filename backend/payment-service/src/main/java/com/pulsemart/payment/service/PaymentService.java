package com.pulsemart.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsemart.payment.domain.OutboxEvent;
import com.pulsemart.payment.domain.OutboxStatus;
import com.pulsemart.payment.domain.Payment;
import com.pulsemart.payment.domain.PaymentStatus;
import com.pulsemart.payment.repository.OutboxRepository;
import com.pulsemart.payment.repository.PaymentRepository;
import com.pulsemart.shared.event.EventEnvelope;
import com.pulsemart.shared.event.EventType;
import com.pulsemart.shared.event.payload.PaymentFailedPayload;
import com.pulsemart.shared.event.payload.PaymentInitiatedPayload;
import com.pulsemart.shared.event.payload.PaymentSucceededPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final Random RANDOM = new Random();

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    @Value("${payment.failure-rate:0.3}")
    private double failureRate;

    /**
     * Process a payment for the given PAYMENT_INITIATED event.
     * Injects a configurable random failure (default 30%).
     * Saves the Payment record and an outbox event in a single @Transactional.
     */
    @Transactional
    public void processPayment(PaymentInitiatedPayload payload) {
        boolean shouldFail = RANDOM.nextDouble() < failureRate;

        Span span = tracer.nextSpan().name("payment.process");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            span.tag("order.id", payload.getOrderId().toString());
            span.tag("payment.failure.injected", String.valueOf(shouldFail));

            Payment payment = Payment.builder()
                    .orderId(payload.getOrderId())
                    .reservationId(payload.getReservationId())
                    .customerId(payload.getCustomerId())
                    .amount(payload.getAmount())
                    .status(shouldFail ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED)
                    .failureReason(shouldFail ? "Simulated payment failure" : null)
                    .build();

            paymentRepository.save(payment);

            if (shouldFail) {
                saveOutboxEvent(EventType.PAYMENT_FAILED.name(), payload.getOrderId(),
                        PaymentFailedPayload.builder()
                                .orderId(payload.getOrderId())
                                .paymentId(payment.getId())
                                .amount(payload.getAmount())
                                .failureReason(payment.getFailureReason())
                                .reservationId(payload.getReservationId())
                                .build());

                span.tag("payment.outcome", "failed");
                meterRegistry.counter("pulsemart.payment.total", "outcome", "failed").increment();
                log.info("Payment FAILED (simulated): orderId={} paymentId={}", payload.getOrderId(), payment.getId());
            } else {
                saveOutboxEvent(EventType.PAYMENT_SUCCEEDED.name(), payload.getOrderId(),
                        PaymentSucceededPayload.builder()
                                .orderId(payload.getOrderId())
                                .paymentId(payment.getId())
                                .amount(payload.getAmount())
                                .build());

                span.tag("payment.outcome", "succeeded");
                meterRegistry.counter("pulsemart.payment.total", "outcome", "succeeded").increment();
                log.info("Payment SUCCEEDED: orderId={} paymentId={}", payload.getOrderId(), payment.getId());
            }
        } finally {
            span.end();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void saveOutboxEvent(String eventType, UUID aggregateId, Object payload) {
        try {
            OutboxEvent outbox = OutboxEvent.builder()
                    .eventId(UUID.randomUUID())
                    .eventType(eventType)
                    .aggregateId(aggregateId)
                    .payload(objectMapper.writeValueAsString(
                            EventEnvelope.builder()
                                    .eventId(UUID.randomUUID())
                                    .eventType(eventType)
                                    .timestamp(Instant.now())
                                    .sourceService("payment-service")
                                    .version("1")
                                    .payload(payload)
                                    .build()))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload for " + eventType, e);
        }
    }
}
