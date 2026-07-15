package com.example.orderservice.web.dto;

import com.example.orderservice.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {
}
