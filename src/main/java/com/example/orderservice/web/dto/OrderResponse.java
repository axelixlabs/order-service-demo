package com.example.orderservice.web.dto;

import com.example.orderservice.domain.OrderItem;
import com.example.orderservice.domain.PurchaseOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String orderNumber,
        String status,
        Long customerId,
        String customerName,
        BigDecimal totalAmount,
        String paymentStatus,
        String shipmentStatus,
        List<LineResponse> items,
        Instant createdAt,
        Instant updatedAt) {

    public record LineResponse(
            Long productId,
            String sku,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {
    }

    public static OrderResponse from(PurchaseOrder order) {
        List<LineResponse> lines = order.getItems().stream()
                .map(OrderResponse::toLine)
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus().name(),
                order.getCustomer().getId(),
                order.getCustomer().getFirstName() + " " + order.getCustomer().getLastName(),
                order.getTotalAmount(),
                order.getPayment() != null ? order.getPayment().getStatus().name() : null,
                order.getShipment() != null ? order.getShipment().getStatus().name() : null,
                lines,
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private static LineResponse toLine(OrderItem item) {
        return new LineResponse(
                item.getProduct().getId(),
                item.getProduct().getSku(),
                item.getProduct().getName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineTotal());
    }
}
