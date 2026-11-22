package com.example.kafka_simple_app.service;

import com.example.kafka_simple_app.dto.CarDTO;
import com.example.kafka_simple_app.dto.SentMessageDTO;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);

    @Value("${topic.name}")
    private String topicName;
    private final KafkaTemplate<String, CarDTO> kafkaTemplate;

    public KafkaProducerService(KafkaTemplate<String, CarDTO> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public SentMessageDTO send(CarDTO carDTO) {
        // The key picks the partition (hash of the key, modulo the partition count), so every
        // car of the same brand lands on the same partition and is consumed in the order sent.
        String key = carDTO.brand();

        // send() is asynchronous. Waiting for the broker's acknowledgement here keeps the demo
        // simple and lets the HTTP response show where the message was stored.
        RecordMetadata metadata = kafkaTemplate.send(topicName, key, carDTO).join().getRecordMetadata();

        log.info("Sent key={} to partition={} offset={}: {}", key, metadata.partition(), metadata.offset(), carDTO);
        return new SentMessageDTO(metadata.topic(), key, metadata.partition(), metadata.offset());
    }
}
