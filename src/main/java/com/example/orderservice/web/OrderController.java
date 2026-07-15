package com.example.orderservice.web;

import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.service.OrderService;
import com.example.orderservice.web.dto.CreateOrderRequest;
import com.example.orderservice.web.dto.OrderResponse;
import com.example.orderservice.web.dto.UpdateOrderStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Hot-path API surface: create, read, and advance orders. These endpoints are
 * expected to receive the bulk of production traffic.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** Hot path #1 — create an order (also emits a Kafka event). */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
                                                UriComponentsBuilder uriBuilder) {
        PurchaseOrder order = orderService.createOrder(request);
        URI location = uriBuilder.path("/api/v1/orders/{id}").buildAndExpand(order.getId()).toUri();
        return ResponseEntity.created(location).body(OrderResponse.from(order));
    }

    /** Hot path #2 — fetch a single order. */
    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    /** Hot path #3 — advance an order's status. */
    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable Long id,
                                      @Valid @RequestBody UpdateOrderStatusRequest request) {
        return OrderResponse.from(orderService.updateStatus(id, request.status()));
    }
}
