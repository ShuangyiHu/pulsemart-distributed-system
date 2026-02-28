package com.pulsemart.shared.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryFailedPayload {
    private UUID orderId;
    private UUID productId;
    private int requestedQuantity;
    private int availableQuantity;
    private String reason;
}
