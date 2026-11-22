package com.example.kafka_simple_app;

import com.example.kafka_simple_app.repository.CarRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigInteger;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class KafkaSimpleAppApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CarRepository carRepository;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Value("${topic.partitions}")
    private int partitions;

    @Test
    void postedCarTravelsThroughKafkaIntoTheDatabase() throws Exception {
        // The consumer starts reading at the latest offset, so a message sent before it has been
        // assigned the topic's partitions would be skipped. Wait for the assignment first.
        listenerRegistry.getListenerContainers()
                .forEach(container -> ContainerTestUtils.waitForAssignment(container, partitions));

        mockMvc.perform(post("/kafka-igor/producer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brand": "Volvo", "model": "XC60", "price": 45000}
                                """))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(carRepository.findAll()).singleElement().satisfies(car -> {
                    assertThat(car.getBrand()).isEqualTo("Volvo");
                    assertThat(car.getModel()).isEqualTo("XC60");
                    assertThat(car.getPrice()).isEqualTo(BigInteger.valueOf(45000));
                }));

        mockMvc.perform(get("/kafka-igor/cars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].brand").value("Volvo"))
                .andExpect(jsonPath("$[0].model").value("XC60"));
    }
}
