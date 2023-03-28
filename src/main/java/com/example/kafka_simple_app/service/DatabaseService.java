package com.example.kafka_simple_app.service;

import com.example.kafka_simple_app.entity.Car;
import com.example.kafka_simple_app.repository.CarRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.StreamSupport;

@Service
public class DatabaseService {

    private final CarRepository carRepository;

    public DatabaseService(CarRepository carRepository) {
        this.carRepository = carRepository;
    }

    public List<Car> getAllCars() {
        return StreamSupport.stream(carRepository.findAll().spliterator(), false).toList();
    }
}
