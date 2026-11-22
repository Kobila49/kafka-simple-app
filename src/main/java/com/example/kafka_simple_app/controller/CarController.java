package com.example.kafka_simple_app.controller;

import com.example.kafka_simple_app.dto.CarDTO;
import com.example.kafka_simple_app.dto.SentMessageDTO;
import com.example.kafka_simple_app.entity.Car;
import com.example.kafka_simple_app.service.CarProducer;
import com.example.kafka_simple_app.service.DatabaseService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cars")
public class CarController {

    private final CarProducer carProducer;
    private final DatabaseService databaseService;

    public CarController(CarProducer carProducer,
                         DatabaseService databaseService) {
        this.carProducer = carProducer;
        this.databaseService = databaseService;
    }

    /**
     * 202 Accepted rather than 201 Created: at this point the car is only in Kafka. The consumer
     * stores it in the database asynchronously, a moment later (or never, if it is invalid).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SentMessageDTO sendCar(@RequestBody CarDTO carDTO) {
        return carProducer.send(carDTO);
    }

    @GetMapping
    public List<Car> getAllCars() {
        return databaseService.getAllCars();
    }
}
