package com.example.orderservice.web.dto;

import com.example.orderservice.domain.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateOrderRequest(
        @NotNull Long customerId,
        @NotNull Long shippingAddressId,
        @NotNull PaymentMethod paymentMethod,
        @NotEmpty @Valid List<OrderLineRequest> items) {

    public record OrderLineRequest(
            @NotNull Long productId,
            @jakarta.validation.constraints.Positive int quantity) {
    }
}
