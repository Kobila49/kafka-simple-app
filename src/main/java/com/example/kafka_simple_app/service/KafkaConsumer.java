package com.example.kafka_simple_app.service;

import com.example.kafka_simple_app.dto.CarDTO;
import com.example.kafka_simple_app.entity.Car;
import com.example.kafka_simple_app.repository.CarRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.text.MessageFormat;

@Slf4j
@Service
public class KafkaConsumer {

    private final CarRepository repository;

    public KafkaConsumer(CarRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "${topic.name}", groupId = "${spring.kafka.group-id}")
    public void consume(CarDTO carDTO) {
        log.info(MessageFormat.format("Received Car information: {0}", carDTO));
        this.repository.save(carEntity(carDTO));
        log.info("Car saved to database");
    }

    private Car carEntity(CarDTO carDTO) {
        return Car.builder()
                .brand(carDTO.brand())
                .model(carDTO.model())
                .price(carDTO.price())
                .build();
    }
}
