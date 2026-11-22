package com.example.kafka_simple_app.service;

import com.example.kafka_simple_app.dto.CarDTO;
import com.example.kafka_simple_app.entity.Car;
import com.example.kafka_simple_app.repository.CarRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class CarConsumer {

    private static final Logger log = LoggerFactory.getLogger(CarConsumer.class);

    private final CarRepository repository;

    public CarConsumer(CarRepository repository) {
        this.repository = repository;
    }

    /**
     * Takes the whole record rather than just the {@code CarDTO}, to show the partition and offset.
     * Exceptions go to the error handler in {@code KafkaErrorHandlingConfig}.
     */
    @KafkaListener(topics = "${topic.name}")
    public void consume(ConsumerRecord<String, CarDTO> consumerRecord) {
        log.info("Consumed key={} partition={} offset={}: {}", consumerRecord.key(),
                consumerRecord.partition(), consumerRecord.offset(), consumerRecord.value());

        // Validated here, not in the REST API, so you can send invalid cars and watch what happens.
        CarDTO car = consumerRecord.value();
        if (car.price() == null || car.price().signum() <= 0) {
            throw new InvalidCarException("Price must be positive: " + car);
        }

        this.repository.save(carEntity(car));
    }

    private Car carEntity(CarDTO carDTO) {
        return new Car(carDTO.brand(), carDTO.model(), carDTO.price());
    }
}
