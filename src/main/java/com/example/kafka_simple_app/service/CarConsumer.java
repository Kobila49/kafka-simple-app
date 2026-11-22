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
     * Receives the whole Kafka record rather than just the payload, to show what a consumer
     * actually gets: the key and value plus the partition and offset the record was read from.
     * <p>
     * Any exception thrown here is handled by the {@code DefaultErrorHandler} from
     * {@code KafkaErrorHandlingConfig}: retried, then published to the dead-letter topic.
     */
    @KafkaListener(topics = "${topic.name}")
    public void consume(ConsumerRecord<String, CarDTO> consumerRecord) {
        log.info("Consumed key={} partition={} offset={}: {}", consumerRecord.key(),
                consumerRecord.partition(), consumerRecord.offset(), consumerRecord.value());

        // The REST API deliberately accepts any car, so you can send invalid ones and watch
        // what the consumer side does with them.
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
