package com.pulsemart.summarizer.api.dto;

import com.pulsemart.summarizer.domain.OrderSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryResponse {

    private UUID id;
    private UUID orderId;
    private String status;
    private String summaryText;
    private String modelUsed;
    private Integer promptTokens;
    private Integer completionTokens;
    private Instant createdAt;

    public static SummaryResponse from(OrderSummary entity) {
        return SummaryResponse.builder()
                .id(entity.getId())
                .orderId(entity.getOrderId())
                .status(entity.getStatus())
                .summaryText(entity.getSummaryText())
                .modelUsed(entity.getModelUsed())
                .promptTokens(entity.getPromptTokens())
                .completionTokens(entity.getCompletionTokens())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
