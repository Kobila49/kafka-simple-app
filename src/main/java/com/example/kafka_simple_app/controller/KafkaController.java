package com.example.kafka_simple_app.controller;

import com.example.kafka_simple_app.dto.CarDTO;
import com.example.kafka_simple_app.dto.SentMessageDTO;
import com.example.kafka_simple_app.entity.Car;
import com.example.kafka_simple_app.service.DatabaseService;
import com.example.kafka_simple_app.service.KafkaProducerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(value = "/kafka-igor")
public class KafkaController {

    private final KafkaProducerService producerService;
    private final DatabaseService databaseService;

    public KafkaController(KafkaProducerService producerService,
                           DatabaseService databaseService) {
        this.producerService = producerService;
        this.databaseService = databaseService;
    }

    @PostMapping("/producer")
    public SentMessageDTO sendMessage(@RequestBody CarDTO carDTO) {
        return producerService.send(carDTO);
    }

    @GetMapping("/cars")
    public ResponseEntity<List<Car>> getAllCars() {
        return ResponseEntity.ok(databaseService.getAllCars());
    }
}
