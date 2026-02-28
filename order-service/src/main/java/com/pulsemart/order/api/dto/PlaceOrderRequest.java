package com.pulsemart.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class PlaceOrderRequest {

    @NotNull
    private UUID customerId;

    @NotEmpty
    @Valid
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {

        @NotNull
        private UUID productId;

        @Min(1)
        private int quantity;

        @NotNull
        @DecimalMin("0.01")
        private BigDecimal unitPrice;
    }
}
