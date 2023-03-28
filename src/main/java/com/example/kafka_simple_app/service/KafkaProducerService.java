package com.example.kafka_simple_app.service;

import com.example.kafka_simple_app.dto.CarDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KafkaProducerService {

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
