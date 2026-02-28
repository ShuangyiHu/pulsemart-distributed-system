package com.pulsemart.shared.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFailedPayload {
    private UUID orderId;
    private UUID paymentId;
    private BigDecimal amount;
    private String failureReason;
    private UUID reservationId;
}
