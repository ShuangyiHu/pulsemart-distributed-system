package com.pulsemart.summarizer.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_summaries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSummary {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    @Column(name = "model_used", nullable = false, length = 60)
    private String modelUsed;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
