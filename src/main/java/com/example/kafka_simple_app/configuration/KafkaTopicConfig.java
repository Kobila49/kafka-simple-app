package com.example.kafka_simple_app.configuration;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

    /**
     * Spring's {@code KafkaAdmin} creates this topic at startup if it doesn't exist yet (and adds
     * partitions if it has fewer). A topic is split into partitions: each one is an ordered log,
     * and the partitions are what get spread across the consumers of a group.
     */
    @Bean
    NewTopic carsTopic(@Value("${topic.name}") String name, @Value("${topic.partitions}") int partitions) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(1) // single broker in compose.yaml, so only one copy is possible
                .build();
    }

    /**
     * Dead-letter topic for messages the consumer gave up on (see {@code KafkaErrorHandlingConfig}).
     * A failed record goes to the same partition number it came from, so this topic needs at
     * least as many partitions as the main one.
     */
    @Bean
    NewTopic carsDeadLetterTopic(@Value("${topic.name}") String name, @Value("${topic.partitions}") int partitions) {
        return TopicBuilder.name(name + "-dlt")
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
