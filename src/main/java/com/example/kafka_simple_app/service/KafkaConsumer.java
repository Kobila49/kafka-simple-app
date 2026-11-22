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
public class KafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumer.class);

    private final CarRepository repository;

    public KafkaConsumer(CarRepository repository) {
        this.repository = repository;
    }

    /**
     * Receives the whole Kafka record rather than just the payload, to show what a consumer
     * actually gets: the key and value plus the partition and offset the record was read from.
     */
    @KafkaListener(topics = "${topic.name}")
    public void consume(ConsumerRecord<String, CarDTO> record) {
        log.info("Consumed key={} partition={} offset={}: {}",
                record.key(), record.partition(), record.offset(), record.value());
        this.repository.save(carEntity(record.value()));
    }

    private Car carEntity(CarDTO carDTO) {
        return new Car(carDTO.brand(), carDTO.model(), carDTO.price());
    }
}
