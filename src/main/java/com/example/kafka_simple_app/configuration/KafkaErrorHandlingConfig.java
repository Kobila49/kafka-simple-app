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

/** Spring Boot applies this error handler to the auto-configured listener container factory. */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> jsonTemplate,
                                     ProducerFactory<Object, Object> producerFactory) {
        // Records that failed deserialization are still raw bytes and are published as they are.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, new KafkaTemplate<>(producerFactory,
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
        templates.put(Object.class, jsonTemplate);
        DeadLetterPublishingRecoverer deadLetterPublisher = new DeadLetterPublishingRecoverer(templates);

        // 2 retries, 1 s apart, then the record goes to the dead-letter topic "<topic>-dlt".
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublisher, new FixedBackOff(1000L, 2L));

        // By default the count restarts when the exception type changes, as it does when the
        // database goes down (first a failed rollback, then failed connects).
        errorHandler.setResetStateOnExceptionChange(false);

        // Retrying won't fix bad data. Deserialization failures aren't retried by default either.
        errorHandler.addNotRetryableExceptions(InvalidCarException.class);

        errorHandler.setRetryListeners(new LoggingRetryListener());
        return errorHandler;
    }

    private static class LoggingRetryListener implements RetryListener {

        private static final Logger log = LoggerFactory.getLogger(LoggingRetryListener.class);

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> consumerRecord, Exception ex, int deliveryAttempt) {
            if (log.isWarnEnabled()) {
                log.warn("Attempt {} failed for key={} partition={} offset={}: {}", deliveryAttempt,
                        consumerRecord.key(), consumerRecord.partition(), consumerRecord.offset(), rootCause(ex));
            }
        }

        @Override
        public void recovered(ConsumerRecord<?, ?> consumerRecord, Exception ex) {
            log.warn("Gave up on key={} partition={} offset={}, published it to {}-dlt", consumerRecord.key(),
                    consumerRecord.partition(), consumerRecord.offset(), consumerRecord.topic());
        }

        private static String rootCause(Exception ex) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
            String message = String.valueOf(cause.getMessage()).lines().findFirst().orElse("");
            return cause.getClass().getSimpleName() + ": " + message;
        }
    }
}
