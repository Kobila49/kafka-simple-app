package com.example.kafka_simple_app.dto;

/**
 * Where the broker stored a produced message: the partition it was appended to and its offset
 * (position) within that partition.
 */
public record SentMessageDTO(String topic, String key, int partition, long offset) {

}
