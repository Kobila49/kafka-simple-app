package com.example.kafka_simple_app.dto;

public record SentMessageDTO(String topic, String key, int partition, long offset) {

}
