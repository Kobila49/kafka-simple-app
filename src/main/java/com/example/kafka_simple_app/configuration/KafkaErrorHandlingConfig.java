package com.example.kafka_simple_app.configuration;

import com.example.kafka_simple_app.service.InvalidCarException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What happens when a message can't be processed. Spring Boot applies this error handler to the
 * auto-configured {@code @KafkaListener} container factory.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> jsonTemplate,
                                     ProducerFactory<Object, Object> producerFactory) {
        // Publishes failed records to "<topic>-dlt", on the same partition as the original
        // (declared in KafkaTopicConfig). Messages that couldn't be deserialized arrive as their
        // original bytes and are published unchanged; everything else is serialized as JSON.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, new KafkaTemplate<>(producerFactory,
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
        templates.put(Object.class, jsonTemplate);
        DeadLetterPublishingRecoverer deadLetterPublisher = new DeadLetterPublishingRecoverer(templates);

        // Retry a failed record 2 more times, 1 second apart, then dead-letter it. Retrying helps
        // with temporary problems such as the database being down.
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublisher, new FixedBackOff(1000L, 2L));

        // Retrying won't fix bad data, so skip the retries for it. Deserialization failures are
        // already in this category by default.
        errorHandler.addNotRetryableExceptions(InvalidCarException.class);

        errorHandler.setRetryListeners(new LoggingRetryListener());
        return errorHandler;
    }

    /** Makes the error handling visible in the log: every failed attempt, and every dead-lettered record. */
    private static class LoggingRetryListener implements RetryListener {

        private static final Logger log = LoggerFactory.getLogger(LoggingRetryListener.class);

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
            log.warn("Attempt {} failed for key={} partition={} offset={}: {}", deliveryAttempt,
                    record.key(), record.partition(), record.offset(), rootCause(ex));
        }

        @Override
        public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
            log.warn("Gave up on key={} partition={} offset={}, published it to {}-dlt",
                    record.key(), record.partition(), record.offset(), record.topic());
        }

        private static String rootCause(Exception ex) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
            String message = String.valueOf(cause.getMessage()).lines().findFirst().orElse("");
            return cause.getClass().getSimpleName() + ": " + message;
        }
    }
}
