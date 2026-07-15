package com.example.orderservice.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Sample consumer demonstrating the other half of the Kafka integration. In a
 * real system this might live in a separate fulfilment service; here it simply
 * logs the event so the end-to-end flow is observable.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    @KafkaListener(
            topics = "${app.kafka.order-events-topic}",
            groupId = "${app.kafka.consumer-group}")
    public void onOrderCreated(@Payload OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent: order={}, customer={}, total={}, items={}",
                event.orderNumber(), event.customerId(), event.totalAmount(), event.items().size());
    }
}
