package com.example.orderservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    /**
     * Auto-creates the order-events topic on startup so a fresh local stack
     * works without manual topic administration.
     */
    @Bean
    public NewTopic orderEventsTopic(@Value("${app.kafka.order-events-topic}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
