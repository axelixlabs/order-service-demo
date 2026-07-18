package com.example.orderservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    /**
     * Auto-creates the order-events topic on startup so a fresh local stack works
     * without manual topic administration. Declaring this {@link NewTopic} makes
     * {@code KafkaAdmin} contact the broker during context refresh; set
     * {@code app.kafka.auto-create-topics=false} to skip it when no broker is
     * reachable (e.g. the AOT/CDS training run in the Docker build).
     */
    @Bean
    @ConditionalOnProperty(name = "app.kafka.auto-create-topics", havingValue = "true", matchIfMissing = true)
    public NewTopic orderEventsTopic(@Value("${app.kafka.order-events-topic}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
