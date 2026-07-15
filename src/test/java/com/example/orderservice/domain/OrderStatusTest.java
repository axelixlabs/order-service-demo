package com.example.orderservice.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void allowsForwardTransitions() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PROCESSING)).isTrue();
        assertThat(OrderStatus.PROCESSING.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
    }

    @Test
    void rejectsBackwardAndSkippingTransitions() {
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CREATED)).isFalse();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.PROCESSING)).isFalse();
    }

    @Test
    void terminalStatesAllowNoTransitions() {
        assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.PAID)).isFalse();
    }

    @Test
    void cancellationAllowedFromEarlyStates() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PROCESSING.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }
}
