package com.example.kafka_simple_app.configuration;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

    /** Spring's {@code KafkaAdmin} creates missing topics at startup (and adds missing partitions). */
    @Bean
    NewTopic carsTopic(@Value("${topic.name}") String name, @Value("${topic.partitions}") int partitions) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(1) // single broker in compose.yaml, so only one copy is possible
                .build();
    }

    /** A dead-lettered record keeps its partition number, so this topic needs as many partitions. */
    @Bean
    NewTopic carsDeadLetterTopic(@Value("${topic.name}") String name, @Value("${topic.partitions}") int partitions) {
        return TopicBuilder.name(name + "-dlt")
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
