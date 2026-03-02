package com.pulsemart.summarizer.service;

import com.pulsemart.shared.event.EventEnvelope;
import com.pulsemart.summarizer.domain.OrderSummary;
import com.pulsemart.summarizer.repository.OrderSummaryRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SummarizerService {

    private final AnthropicClient anthropicClient;
    private final OrderSummaryRepository summaryRepository;
    private final Tracer tracer;

    @Transactional
    public OrderSummary summarizeOrder(UUID orderId, String terminalStatus, List<EventEnvelope> sagaEvents) {
        Optional<OrderSummary> existing = summaryRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Summary already exists for orderId={}, skipping", orderId);
            return existing.get();
        }

        Span span = tracer.nextSpan().name("ai.summarize");
        try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
            span.tag("orderId", orderId.toString());
            span.tag("terminalStatus", terminalStatus);

            String prompt = buildPrompt(orderId, terminalStatus, sagaEvents);
            AnthropicClient.SummaryResult result = anthropicClient.summarize(prompt);

            span.tag("model", result.model());
            span.tag("promptTokens", String.valueOf(result.promptTokens()));
            span.tag("completionTokens", String.valueOf(result.completionTokens()));

            OrderSummary summary = OrderSummary.builder()
                    .orderId(orderId)
                    .status(terminalStatus)
                    .summaryText(result.text())
                    .modelUsed(result.model())
                    .promptTokens(result.promptTokens())
                    .completionTokens(result.completionTokens())
                    .build();

            summaryRepository.save(summary);
            log.info("Saved AI summary for orderId={} status={}", orderId, terminalStatus);
            return summary;
        } finally {
            span.end();
        }
    }

    @Transactional
    public OrderSummary regenerateSummary(UUID orderId, List<EventEnvelope> sagaEvents) {
        summaryRepository.findByOrderId(orderId).ifPresent(summaryRepository::delete);

        String status = sagaEvents.isEmpty() ? "UNKNOWN" :
                sagaEvents.get(sagaEvents.size() - 1).getEventType();
        return summarizeOrder(orderId, status, sagaEvents);
    }

    @Transactional(readOnly = true)
    public Optional<OrderSummary> getSummary(UUID orderId) {
        return summaryRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> getAllSummaries() {
        return summaryRepository.findAll();
    }

    private String buildPrompt(UUID orderId, String terminalStatus, List<EventEnvelope> sagaEvents) {
        String eventsTimeline = sagaEvents.stream()
                .map(e -> String.format("- [%s] %s from %s: %s",
                        e.getTimestamp(), e.getEventType(), e.getSourceService(), e.getPayload()))
                .collect(Collectors.joining("\n"));

        return String.format("""
                You are an order lifecycle analyst for PulseMart, an e-commerce platform.
                Summarize the following order saga in 2-4 sentences. Include:
                - What the customer ordered (products, quantities, amounts)
                - Whether inventory was successfully reserved
                - Payment outcome
                - Final order status and any compensation steps taken

                Order ID: %s
                Final Status: %s

                Event Timeline:
                %s

                Provide a concise, human-readable summary.
                """, orderId, terminalStatus, eventsTimeline);
    }
}
