package com.example.orderservice.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes {@link OrderCreatedEvent}s to Kafka. Uses the order number as the
 * message key so all events for an order land on the same partition and keep
 * their relative order.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    private final String topic;

    public OrderEventPublisher(KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate,
                               @Value("${app.kafka.order-events-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publishOrderCreated(OrderCreatedEvent event) {
        CompletableFuture<?> future = kafkaTemplate.send(topic, event.orderNumber(), event);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish OrderCreatedEvent for order {}", event.orderNumber(), ex);
            } else {
                log.debug("Published OrderCreatedEvent for order {}", event.orderNumber());
            }
        });
    }
}
