package com.example.kafka_simple_app.service;

import com.example.kafka_simple_app.dto.CarDTO;
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

    public void send(CarDTO carDTO) {
        log.info("Sending Car Json Serializer: {}", carDTO);
        kafkaTemplate.send(topicName, carDTO);
    }
}
