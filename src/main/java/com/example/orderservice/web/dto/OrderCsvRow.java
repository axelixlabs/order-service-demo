package com.example.orderservice.web.dto;

import com.example.orderservice.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Flat projection for the CSV export. Everything the report needs — including the
 * customer email and the line-item count — is computed by a single SQL query, so
 * writing a row never triggers a lazy load (no N+1).
 */
public record OrderCsvRow(
        String orderNumber,
        OrderStatus status,
        String customerEmail,
        long itemCount,
        BigDecimal totalAmount,
        Instant createdAt) {
}
