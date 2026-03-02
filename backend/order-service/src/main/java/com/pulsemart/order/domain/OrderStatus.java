package com.pulsemart.order.domain;

public enum OrderStatus {
    PENDING,
    INVENTORY_RESERVED,
    PAYMENT_PENDING,
    COMPLETED,
    CANCELLED
}
