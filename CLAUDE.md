# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The purpose of this project is to demonstrate how Kafka works, so favor changes that make Kafka behavior visible and easy to follow over production-style abstraction.

## Build, run & test

Spring Boot 4.2 / Java 25 / Gradle 9.9 (wrapper). The build uses a Java 25 toolchain, so Gradle itself can run on any JDK 17+ and finds JDK 25 on its own (e.g. from `~/.jdks`). Docker is required for running the app and for the tests.

```bash
./gradlew build                      # compile + test
./gradlew test --tests 'com.example.kafka_simple_app.KafkaSimpleAppApplicationTests'
./gradlew bootRun                    # starts compose.yaml, then the app on port 9001
docker compose up -d                 # start infrastructure only (bootRun detects it's already running)
docker compose down -v               # stop and wipe everything, including Postgres data
```

There is no linter or formatter task in the build. The IntelliJ project has the google-java-format plugin enabled.

## Infrastructure (`compose.yaml`)

- **kafka** (`apache/kafka`, single node in KRaft mode): `localhost:9092` from the host, `kafka:19092` from other containers. Topics are auto-created on first use; the app does not declare a `NewTopic` bean yet.
- **kafka-ui** (`kafbat/kafka-ui`): http://localhost:8080 for topics, messages, offsets and consumer groups. It is labeled `org.springframework.boot.ignore` so the app neither waits for it nor tries to connect to it.
- **postgres** (18): database/user/password all `cars`, with data in the `postgres-data` volume. The schema comes from `ddl-auto=update`; there are no migrations.

`spring-boot-docker-compose` is a `developmentOnly` dependency. On `bootRun` it runs `docker compose up`, waits for the healthchecks, supplies the Postgres connection details, and runs `docker compose stop` on shutdown. It has **no Kafka service connection**, so the Kafka address always comes from `spring.kafka.bootstrap-servers`.

## Architecture

The whole app is a single round trip through Kafka:

```
POST /kafka-igor/producer (CarDTO JSON)
  → KafkaProducerService → KafkaTemplate<String, CarDTO> → topic `${topic.name}`
  → KafkaConsumer (@KafkaListener) → maps CarDTO → Car entity → CarRepository (Postgres)
GET /kafka-igor/cars → DatabaseService → CarRepository.findAll()
```

- `CarDTO` (a record) is the Kafka message payload. `Car` (a plain JPA entity, no Lombok) is what gets stored, and the GET endpoint returns entities directly. If you add a field, update both classes plus the mapping in `KafkaConsumer.carEntity`.
- Writes go straight from `KafkaConsumer` to `CarRepository`. Reads go through `DatabaseService`.
- Spring Boot 4 uses Jackson 3, so the Kafka JSON classes are `JacksonJsonSerializer`/`JacksonJsonDeserializer`. The older `JsonSerializer`/`JsonDeserializer` are the deprecated Jackson 2 variants.

### Kafka configuration is manual

`configuration/KafkaProducerConfig` and `configuration/KafkaConsumerConfig` define their own `ProducerFactory`, `KafkaTemplate`, `ConsumerFactory` and `kafkaListenerContainerFactory` beans, typed to `<String, CarDTO>`. Because these beans replace Spring Boot's Kafka auto-configuration, **`spring.kafka.producer.*` / `spring.kafka.consumer.*` properties have no effect**, and neither do Kafka `ConnectionDetails` (e.g. `@ServiceConnection`). Change client settings (for example `auto.offset.reset`, which defaults to `latest`) in these config classes.

The only properties read are the ones injected with `@Value`:
- `spring.kafka.bootstrap-servers`
- `spring.kafka.group-id`: a custom key, not the standard Boot property `spring.kafka.consumer.group-id`
- `topic.name`: used by the producer, the consumer's `@KafkaListener`, and the controller's response message

## Tests

`KafkaSimpleAppApplicationTests` is an end-to-end test: POST via MockMvc, wait (Awaitility) for the row in Postgres, then GET. `TestcontainersConfiguration` starts throwaway Kafka and Postgres containers. Postgres is wired with `@ServiceConnection`; Kafka is wired with a `DynamicPropertyRegistrar` that sets `spring.kafka.bootstrap-servers`, because of the manual config above.

Since the consumer starts at the latest offset, a test that produces a message must first wait for the listener to be assigned its partition (`ContainerTestUtils.waitForAssignment`), or the message can be skipped.
