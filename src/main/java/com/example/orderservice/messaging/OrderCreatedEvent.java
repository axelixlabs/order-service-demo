package com.example.orderservice.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Domain event published to Kafka when an order is created on the hot path.
 * Kept as a flat, self-contained record so downstream consumers never need to
 * call back into this service.
 */
public record OrderCreatedEvent(
        Long orderId,
        String orderNumber,
        Long customerId,
        BigDecimal totalAmount,
        String paymentMethod,
        List<Line> items,
        Instant occurredAt) {

    public record Line(Long productId, String sku, int quantity, BigDecimal lineTotal) {
    }
}
