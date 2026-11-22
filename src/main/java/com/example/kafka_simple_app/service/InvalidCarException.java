package com.example.kafka_simple_app.service;

public class InvalidCarException extends RuntimeException {

    public InvalidCarException(String message) {
        super(message);
    }
}
