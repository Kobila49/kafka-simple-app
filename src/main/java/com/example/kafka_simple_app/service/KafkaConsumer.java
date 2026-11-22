package com.example.kafka_simple_app.service;

import com.example.kafka_simple_app.dto.CarDTO;
import com.example.kafka_simple_app.entity.Car;
import com.example.kafka_simple_app.repository.CarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class KafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumer.class);

    private final CarRepository repository;

    public KafkaConsumer(CarRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "${topic.name}")
    public void consume(CarDTO carDTO) {
        log.info("Received Car information: {}", carDTO);
        this.repository.save(carEntity(carDTO));
        log.info("Car saved to database");
    }

    private Car carEntity(CarDTO carDTO) {
        return new Car(carDTO.brand(), carDTO.model(), carDTO.price());
    }
}
