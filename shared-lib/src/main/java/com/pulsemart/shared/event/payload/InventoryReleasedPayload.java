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
public class InventoryReleasedPayload {
    private UUID orderId;
    private UUID reservationId;
    private UUID productId;
    private int quantity;
    private String reason;
}
