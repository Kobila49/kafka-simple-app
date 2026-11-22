package com.example.kafka_simple_app;

import com.example.kafka_simple_app.entity.Car;
import com.example.kafka_simple_app.repository.CarRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests: HTTP -> Kafka -> consumer -> PostgreSQL. All tests share the same containers,
 * so each one uses its own car models and only asserts on those.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class KafkaSimpleAppApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CarRepository carRepository;

    @Value("${topic.name}")
    private String topicName;

    @Test
    void postedCarTravelsThroughKafkaIntoTheDatabase() throws Exception {
        mockMvc.perform(post("/kafka-igor/producer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brand": "Volvo", "model": "XC60", "price": 45000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value(topicName))
                .andExpect(jsonPath("$.key").value("Volvo"))
                .andExpect(jsonPath("$.partition").isNumber())
                .andExpect(jsonPath("$.offset").isNumber());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(storedCar("XC60")).hasValueSatisfying(car -> {
                    assertThat(car.getBrand()).isEqualTo("Volvo");
                    assertThat(car.getPrice()).isEqualTo(BigInteger.valueOf(45000));
                }));

        mockMvc.perform(get("/kafka-igor/cars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.model == 'XC60')].brand").value("Volvo"));
    }

    @Test
    void carsWithTheSameBrandGoToTheSamePartitionInOrder() throws Exception {
        String first = sendCar("Saab", "900");
        String second = sendCar("Saab", "9-3");

        int firstPartition = JsonPath.read(first, "$.partition");
        int secondPartition = JsonPath.read(second, "$.partition");
        long firstOffset = JsonPath.<Number>read(first, "$.offset").longValue();
        long secondOffset = JsonPath.<Number>read(second, "$.offset").longValue();

        assertThat(secondPartition).isEqualTo(firstPartition);
        assertThat(secondOffset).isGreaterThan(firstOffset);
    }

    private String sendCar(String brand, String model) throws Exception {
        return mockMvc.perform(post("/kafka-igor/producer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brand": "%s", "model": "%s", "price": 10000}
                                """.formatted(brand, model)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Optional<Car> storedCar(String model) {
        return StreamSupport.stream(carRepository.findAll().spliterator(), false)
                .filter(car -> car.getModel().equals(model))
                .findFirst();
    }
}
