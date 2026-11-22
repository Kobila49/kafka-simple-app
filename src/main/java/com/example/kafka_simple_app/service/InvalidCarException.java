package com.example.kafka_simple_app.service;

/**
 * A car message that can never be stored. Retrying won't help, so the error handler sends it
 * straight to the dead-letter topic.
 */
public class InvalidCarException extends RuntimeException {

    public InvalidCarException(String message) {
        super(message);
    }
}
