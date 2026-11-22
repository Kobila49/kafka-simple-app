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
public class CarProducer {

    private static final Logger log = LoggerFactory.getLogger(CarProducer.class);

    @Value("${topic.name}")
    private String topicName;
    private final KafkaTemplate<String, CarDTO> kafkaTemplate;

    public CarProducer(KafkaTemplate<String, CarDTO> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public SentMessageDTO send(CarDTO carDTO) {
        // The key's hash picks the partition, so all cars of a brand stay in one partition, in order.
        String key = carDTO.brand();

        // Waits for the broker's acknowledgement, so the response can say where the message went.
        RecordMetadata metadata = kafkaTemplate.send(topicName, key, carDTO).join().getRecordMetadata();

        log.info("Sent key={} to partition={} offset={}: {}", key, metadata.partition(), metadata.offset(), carDTO);
        return new SentMessageDTO(metadata.topic(), key, metadata.partition(), metadata.offset());
    }
}
