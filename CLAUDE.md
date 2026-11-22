# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The purpose of this project is to demonstrate how Kafka works, so favor changes that make Kafka behavior visible and easy to follow over production-style abstraction. `README.md` walks through demo scenarios with exact commands and expected log output (including which brand lands on which partition). When you change Kafka behavior, log messages, topic names or partition counts, update the README to match.

## Build, run & test

Spring Boot 4.2 / Java 25 / Gradle 9.9 (wrapper). The build uses a Java 25 toolchain, so Gradle itself can run on any JDK 17+ and finds JDK 25 on its own (e.g. from `~/.jdks`). Docker is required for running the app and for the tests.

```bash
./gradlew build                      # compile + test
./gradlew test --tests 'com.example.kafka_simple_app.DeadLetterTopicTests'
./gradlew bootRun                    # starts compose.yaml, then the app on port 9001
./gradlew bootRun --args='--server.port=9002'   # second instance, e.g. for consumer group demos
docker compose up -d                 # start infrastructure only (bootRun then leaves it alone)
docker compose --profile app up -d --build   # infrastructure + the app as a container (no JDK needed)
docker compose run -d --name app-2 app        # extra app instance without a host port (consumer group demos)
docker compose --profile app down -v # stop and wipe everything, including Kafka and Postgres data
```

Always pass `--profile app` to `down`. Without it, Compose leaves the app containers running and can't remove the network.

There is no linter or formatter task in the build. The IntelliJ project has the google-java-format plugin enabled.

## Infrastructure (`compose.yaml`)

- **kafka** (`apache/kafka`, single node in KRaft mode): `localhost:9092` from the host, `kafka:19092` from other containers. **Topic auto-creation is disabled**: every topic must be declared as a `NewTopic` bean. Kafka CLI tools are at `/opt/kafka/bin/` in the container (`docker compose exec kafka ...`).
- **kafka-ui** (`kafbat/kafka-ui`): http://localhost:8080. It is labeled `org.springframework.boot.ignore` so the app neither waits for it nor tries to connect to it.
- **postgres** (18): database/user/password all `cars`, with data in the `postgres-data` volume. The schema comes from `ddl-auto=update`; there are no migrations.
- **app** (profile `app`, built from `Dockerfile`): only starts with `--profile app`, so `bootRun` (whose Compose support activates no profiles) never starts a second copy of the app on port 9001. The service points the app at `kafka:19092` and `postgres:5432` with `SPRING_KAFKA_BOOTSTRAP_SERVERS` / `SPRING_DATASOURCE_URL`. The host port is fixed at 9001. Don't switch to a port range for `--scale`: Docker assigns ports from a range round-robin, so a single instance wouldn't reliably get 9001. Extra instances come from `docker compose run`, which doesn't publish ports. `down` doesn't remove `run` containers, so remove them with `docker rm -f` (or `down --remove-orphans`).

The `Dockerfile` has three stages, and the image is about 180 MB:
- **`build`** (`eclipse-temurin:25-jdk-alpine`): runs `./gradlew bootJar` with a BuildKit cache mount for `~/.gradle`, skipping tests because they need Docker themselves. It extracts the jar into layers (`-Djarmode=tools extract --layers`) and writes the JDK modules the app references to `jdk-modules.txt` with `jdeps`.
- **`jre`**: runs `jlink` with exactly those modules. It is a separate stage, so the runtime is only linked again (and its layer only changes) when the module list changes.
- **runtime** (`alpine:3.24`, the same Alpine release as the JDK image, because the linked runtime uses musl): runs `java -jar application.jar` as the non-root user `spring`.

`jdeps` misses modules that are only loaded by reflection or `ServiceLoader`. If the container fails with `ClassNotFoundException` for a JDK class after a dependency change, add that module to `--add-modules`. The jlink options were measured: `--compress` makes the download bigger, because registries gzip layers anyway, and `--generate-cds-archive` adds 27 MB without faster startup. `devtools` and `spring-boot-docker-compose` are `developmentOnly`, so they aren't in the image. `.dockerignore` limits the build context to the wrapper, build files and `src/`.

`spring-boot-docker-compose` is a `developmentOnly` dependency. On `bootRun` it runs `docker compose up`, waits for the healthchecks, supplies the Postgres connection details, and runs `docker compose stop` on shutdown, but only if it started the containers itself. It has **no Kafka service connection**, so the Kafka address always comes from `spring.kafka.bootstrap-servers`.

## Architecture

```
POST /api/cars (CarDTO JSON) → CarController, answers 202 Accepted
  → CarProducer: key = brand, waits for the ack, returns SentMessageDTO (partition/offset)
  → topic `${topic.name}` (cars, `${topic.partitions}` = 3 partitions)
  → CarConsumer (@KafkaListener, gets ConsumerRecord) → validates → Car entity → CarRepository
     on exception → DefaultErrorHandler → retry → DeadLetterPublishingRecoverer → `cars-dlt`
GET /api/cars → DatabaseService → CarRepository.findAll()
```

- **All Kafka client settings are Spring Boot properties** in `application.yml` (`spring.kafka.*`); there are no hand-built producer/consumer factories. Boot applies the `DefaultErrorHandler` bean from `KafkaErrorHandlingConfig` to the auto-configured listener container factory.
- **Message format:** plain JSON with no `__TypeId__` header (`spring.json.add.type.headers: false`). The consumer reads values as `CarDTO` through `spring.json.value.default.type`, behind an `ErrorHandlingDeserializer`, so invalid JSON reaches the error handler instead of failing every poll. Messages produced by other tools (console producer, Kafka UI) work the same way.
- **Error handling** (`KafkaErrorHandlingConfig`): `FixedBackOff(1s, 2 retries)`, then dead-letter. `InvalidCarException` (price ≤ 0, thrown by the consumer) and deserialization failures are not retryable and go straight to the DLT. The DLT keeps the source partition, so `KafkaTopicConfig` declares it with the same partition count. The recoverer has two templates: `byte[]` values (from deserialization failures) are published as raw bytes, everything else as JSON. A `RetryListener` logs each failed attempt and each dead-lettered record.
- Spring Kafka 4 names the default DLT `<topic>-dlt`, not the older `<topic>.DLT`.
- `spring.datasource.hikari.connection-timeout` is 3s on purpose, so the "database down → retries → DLT" demo finishes quickly.
- `CarDTO` is the Kafka payload and `Car` is the JPA entity (no Lombok). The GET endpoint returns entities directly. If you add a field, update both classes plus `CarConsumer.carEntity`.

## Tests

`KafkaSimpleAppApplicationTests` (happy path, partitioning by key) and `DeadLetterTopicTests` (invalid car, non-JSON message) are end-to-end tests through MockMvc. They share one cached Spring context and one set of containers from `TestcontainersConfiguration`, which wires Kafka and Postgres with `@ServiceConnection`. Because the data is shared, each test uses its own brands/models and only asserts on those. Never assert on the total contents of the DB or a topic.

The consumer uses `auto-offset-reset: earliest`, so tests can send right away without waiting for partition assignment. Asynchronous results are awaited with Awaitility. DLT assertions read the `-dlt` topic with a raw consumer in a fresh consumer group.
