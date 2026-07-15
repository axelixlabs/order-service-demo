package com.example.orderservice.domain;

import java.util.Set;

/**
 * Lifecycle of a {@link PurchaseOrder}. The enum also encodes the legal
 * transitions so the service layer can reject invalid status changes.
 */
public enum OrderStatus {
    CREATED,
    PAID,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    private static final java.util.Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = java.util.Map.of(
            CREATED, Set.of(PAID, CANCELLED),
            PAID, Set.of(PROCESSING, CANCELLED),
            PROCESSING, Set.of(SHIPPED, CANCELLED),
            SHIPPED, Set.of(DELIVERED),
            DELIVERED, Set.of(),
            CANCELLED, Set.of());

    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
