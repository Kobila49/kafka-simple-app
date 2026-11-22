package com.example.kafka_simple_app;

import com.example.kafka_simple_app.repository.CarRepository;
import com.example.kafka_simple_app.service.InvalidCarException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Messages the consumer can't process end up on the dead-letter topic instead of blocking the
 * partition. Shares the application context (and containers) with the other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeadLetterTopicTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CarRepository carRepository;

    @Autowired
    private KafkaContainer kafka;

    @Value("${topic.name}")
    private String topicName;

    @Test
    void invalidCarIsSentToTheDeadLetterTopic() throws Exception {
        mockMvc.perform(post("/api/cars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brand": "Trabant", "model": "601", "price": -1}
                                """))
                .andExpect(status().isAccepted());

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter("Trabant");

        assertThat(deadLetter.value()).contains("\"model\":\"601\"");
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(InvalidCarException.class.getName());
        assertThat(carRepository.findAll()).noneMatch(car -> car.getModel().equals("601"));
    }

    @Test
    void unreadableMessageIsSentToTheDeadLetterTopicAndConsumptionContinues() throws Exception {
        try (Producer<String, String> producer = new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topicName, "poison", "this is not json")).get();
        }

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter("poison");
        assertThat(deadLetter.value()).isEqualTo("this is not json");

        // The bad message didn't block the consumer: a valid car sent afterwards is still stored.
        mockMvc.perform(post("/api/cars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brand": "Lada", "model": "Niva", "price": 9000}
                                """))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(30)).until(() ->
                StreamSupport.stream(carRepository.findAll().spliterator(), false)
                        .anyMatch(car -> car.getModel().equals("Niva")));
    }

    /** Reads the dead-letter topic from the beginning until a record with the given key shows up. */
    private ConsumerRecord<String, String> awaitDeadLetter(String key) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new KafkaConsumer<>(
                props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topicName + "-dlt"));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(received::add);
                return received.stream().anyMatch(record -> key.equals(record.key()));
            });
            return received.stream().filter(record -> key.equals(record.key())).findFirst().orElseThrow();
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
