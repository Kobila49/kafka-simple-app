package com.example.kafka_simple_app.controller;

import com.example.kafka_simple_app.dto.CarDTO;
import com.example.kafka_simple_app.entity.Car;
import com.example.kafka_simple_app.service.DatabaseService;
import com.example.kafka_simple_app.service.KafkaProducerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(value = "/kafka-igor")
public class KafkaController {

    @Value("${topic.name}")
    private String topicName;
    private final KafkaProducerService producerService;
    private final DatabaseService databaseService;

    public KafkaController(KafkaProducerService producerService,
                           DatabaseService databaseService) {
        this.producerService = producerService;
        this.databaseService = databaseService;
    }

    @PostMapping("/producer")
    public String sendMessage(@RequestBody CarDTO carDTO) {
        producerService.send(carDTO);
        return "Message sent successfully to the Kafka topic %s".formatted(topicName);
    }

    @GetMapping("/cars")
    public ResponseEntity<List<Car>>getAllCars() {
        return ResponseEntity.ok(databaseService.getAllCars());
    }
}
