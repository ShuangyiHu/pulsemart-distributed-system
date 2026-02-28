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
public class InventoryReservedPayload {
    private UUID orderId;
    private UUID reservationId;
    private UUID productId;
    private int quantity;
    private BigDecimal totalAmount;
}
