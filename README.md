# Kafka Simple App

A small Spring Boot application for learning how Apache Kafka works. A REST endpoint produces car messages to a Kafka topic, a consumer reads them and stores them in PostgreSQL, and a second endpoint lists what was stored:

```
POST /api/cars ──► CarProducer ──► topic "cars" (3 partitions) ──► CarConsumer ──► PostgreSQL
                                                                        │
                                                              fails? retry, then
                                                                        ▼
GET /api/cars ◄── DatabaseService ◄── PostgreSQL                  topic "cars-dlt"
```

## Requirements

- **Docker** with Docker Compose. This is all you need to [run everything in Docker](#running-everything-in-docker).
- **JDK 25** to run the app on your machine with Gradle, and for the tests. Gradle runs on any JDK 17+ and finds JDK 25 itself; if it can't, point `JAVA_HOME` at a JDK 25.

## Quick start

```bash
./gradlew bootRun
```

This starts the containers from `compose.yaml` (Kafka, Kafka UI, PostgreSQL), waits until they're healthy, and starts the app on port 9001. When you stop the app, the containers are stopped too.

| What | Where |
|---|---|
| REST API | http://localhost:9001/api/cars |
| Kafka UI (topics, messages, consumer groups) | http://localhost:8080 |
| Kafka broker | `localhost:9092` |
| PostgreSQL | `localhost:5432`, database/user/password `cars` |

Send a car:

```bash
curl -X POST localhost:9001/api/cars \
  -H 'Content-Type: application/json' \
  -d '{"brand": "Volvo", "model": "XC60", "price": 45000}'
```

The API answers `202 Accepted` with where Kafka stored the message:

```json
{
  "topic" : "cars",
  "key" : "Volvo",
  "partition" : 0,
  "offset" : 0
}
```

The producer's `send()` is asynchronous, but the app waits for the broker's acknowledgement before it answers, which is how it knows the partition and offset. "Accepted" because at this point the car is only in Kafka. The consumer stores it in the database asynchronously, a moment later. The app log shows both sides:

```
Sent key=Volvo to partition=0 offset=0: CarDTO[brand=Volvo, model=XC60, price=45000]
Consumed key=Volvo partition=0 offset=0: CarDTO[brand=Volvo, model=XC60, price=45000]
```

List stored cars with `curl localhost:9001/api/cars`. To start over with empty Kafka and PostgreSQL: `docker compose --profile app down -v`.

## Running everything in Docker

No JDK needed: the `Dockerfile` builds the app inside a container. The image is about 180 MB: Alpine Linux, the app, and a Java runtime that `jlink` assembles from only the JDK modules the app uses. The JDK and Gradle are only needed for the build, so they aren't in the image. The app's service in `compose.yaml` belongs to the Compose profile `app`, so it only starts when you ask for it. That's why `./gradlew bootRun` and a plain `docker compose up` start just the infrastructure.

```bash
docker compose --profile app up -d --build   # build the image and start everything
docker compose logs -f app                   # follow the app log
docker compose --profile app down            # stop everything, keeping Kafka's and PostgreSQL's data
```

The URLs are the same as above. Run the first command again after changing code, and `--build` rebuilds the image. Always pass `--profile app` to `down`: without it, the app containers keep running.

Inside Docker, the app reaches Kafka at `kafka:19092` and PostgreSQL at `postgres:5432` instead of `localhost`; `compose.yaml` sets this through environment variables. That's why Kafka has two listeners: `localhost:9092` for clients on your machine, `kafka:19092` for clients in other containers.

## Where each Kafka concept lives

| Concept | Where to look |
|---|---|
| Producer, serialization, consumer group, offset reset, concurrency | `src/main/resources/application.yml` (`spring.kafka.*`) |
| Topic and partitions, dead-letter topic | `configuration/KafkaTopicConfig.java` |
| Message key → partition, waiting for the broker's acknowledgement | `service/CarProducer.java` |
| Consumer, `ConsumerRecord` (key, partition, offset) | `service/CarConsumer.java` |
| Retries and dead-lettering | `configuration/KafkaErrorHandlingConfig.java` |
| The broker itself (KRaft mode, listeners) | `compose.yaml` |

The broker's automatic topic creation is turned off. The app declares `cars` and `cars-dlt` and creates them at startup if they don't exist yet, so a typo in a topic name fails instead of quietly creating a new topic.

Kafka stores keys and values as plain bytes and doesn't care about their format. Here the key is a string and the value is plain JSON. The producer doesn't add Spring's `__TypeId__` header with a Java class name, and the consumer reads every value as a car. So messages from other tools, like Kafka's console producer or Kafka UI, are read the same way.

## Demo scenarios

### 1. Keys decide the partition

The car's brand is the message key. The producer hashes the key and takes the result modulo the number of partitions, so all cars of one brand go to the same partition, where they keep their order. With 3 partitions, Volvo goes to partition 0, Honda to 1 and Fiat to 2:

```bash
for brand in Volvo Honda Fiat Volvo; do
  curl -s -X POST localhost:9001/api/cars -H 'Content-Type: application/json' \
    -d "{\"brand\": \"$brand\", \"model\": \"Demo\", \"price\": 1000}"; echo
done
```

Offsets count up separately in each partition: the second Volvo gets the next offset in partition 0. In Kafka UI, open **Topics → cars → Messages** to see each message with its partition, offset and key.

### 2. Consumer groups share the partitions

All app instances use the consumer group `car-db-writer`. Kafka gives each partition to exactly one member of the group, and redistributes them ("rebalances") when members join or leave.

Start the containers yourself first, so they don't stop when one app instance does:

```bash
docker compose up -d
./gradlew bootRun                                # terminal 1, port 9001
./gradlew bootRun --args='--server.port=9002'    # terminal 2
```

When the second instance joins, the first one's log shows the rebalance:

```
car-db-writer: partitions revoked: [cars-0, cars-1, cars-2]
car-db-writer: partitions assigned: [cars-0, cars-1]
```

and the second gets `[cars-2]`. Send the Volvo, Honda and Fiat cars from scenario 1 again: the Fiat is consumed by the instance that owns partition 2. Then stop one instance and watch the other take all partitions back.

In Docker, start the extra instances with `docker compose run`:

```bash
docker compose --profile app up -d          # first instance, on port 9001
docker compose run -d --name app-2 app      # two more in the same consumer group
docker compose run -d --name app-3 app
docker logs app-2 | grep 'partitions assigned'
```

After the rebalances settle, each of the three containers has one partition. `run` doesn't publish the service's port, so the extra instances don't clash with the first one: keep sending cars to port 9001, and each one is consumed by whichever instance owns its partition. Stop an instance with `docker stop app-2` to see the rebalance, and remove both with `docker rm -f app-2 app-3` when you're done.

A fourth instance would get no partition and sit idle: **a group can't use more consumers than the topic has partitions**. Instead of more instances, you can also run more consumer threads in one instance with `--spring.kafka.listener.concurrency=3`. Each thread is a separate member of the group.

See the assignment and progress of the group from the command line (or under **Consumers** in Kafka UI):

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group car-db-writer
```

`CURRENT-OFFSET` is how far the group has read, `LOG-END-OFFSET` is how far the partition goes, and `LAG` is the difference.

### 3. Kafka keeps messages; offsets decide what gets read

Kafka doesn't delete a message when it's consumed. Each consumer group only stores its position (offset) per partition, and that's what makes lag and replay possible.

**Lag.** Stop all app instances but keep the other containers running (`docker compose stop app` if the app runs in Docker). Produce a message directly with Kafka's console producer (or with **Produce Message** in Kafka UI):

```bash
echo 'Volvo:{"brand": "Volvo", "model": "V70", "price": 3000}' | \
  docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic cars \
  --reader-property parse.key=true --reader-property key.separator=:
```

`kafka-consumer-groups.sh --describe` now shows `LAG 1` on partition 0. Start the app again and it catches up, consuming the V70 that was sent while it was down.

**Replay.** Stop the app again and move the group's offsets back to the beginning (only possible while the group has no active members):

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group car-db-writer --topic cars \
  --reset-offsets --to-earliest --execute
```

Start the app and it consumes every message again, so every car is stored a second time. Kafka guarantees each message is delivered *at least once*, so real consumers need to be idempotent, for example by ignoring messages they've already processed.

**New groups.** A group with no stored offsets starts according to `spring.kafka.consumer.auto-offset-reset`, which is `earliest` here (the whole topic). Kafka's default, `latest`, would only read messages sent after the group started. Once a group has stored offsets, it always continues from them. Try `./gradlew bootRun --args='--spring.kafka.consumer.group-id=another-group'`: it reads everything from the start, independently of `car-db-writer`.

To read the topic without joining any group the app uses:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic cars --from-beginning \
  --formatter-property print.key=true --formatter-property print.partition=true \
  --formatter-property print.offset=true
```

### 4. Bad messages go to the dead-letter topic

A car with a price of 0 or less is rejected by the consumer. Retrying won't fix it, so it goes straight to `cars-dlt`:

```bash
curl -X POST localhost:9001/api/cars -H 'Content-Type: application/json' \
  -d '{"brand": "Volvo", "model": "Broken", "price": -1}'
```

```
Attempt 1 failed for key=Volvo partition=0 offset=...: InvalidCarException: Price must be positive: ...
Gave up on key=Volvo partition=0 offset=..., published it to cars-dlt
```

The same happens to a message that isn't valid JSON. Produce `Volvo:not json` with the console producer from scenario 3, or with Kafka UI. Without the dead-letter topic, a message like that would block its partition: the consumer would fail on it on every poll and never get past it.

In Kafka UI, open **Topics → cars-dlt → Messages**. Each dead-lettered message keeps its original key, value and partition, plus `kafka_dlt-*` headers that record the exception and where the message came from (original topic, partition, offset, consumer group).

### 5. Temporary failures are retried

Stop the database and send a valid car:

```bash
docker compose stop postgres
curl -X POST localhost:9001/api/cars -H 'Content-Type: application/json' \
  -d '{"brand": "Honda", "model": "Civic", "price": 25000}'
```

The consumer tries 3 times, about 4 seconds apart (3 s database connection timeout plus 1 s back-off), then gives up and dead-letters the message:

```
Attempt 1 failed for key=Honda partition=1 offset=...: ConnectException: Connection refused
Attempt 2 failed for key=Honda partition=1 offset=...: ConnectException: Connection refused
Attempt 3 failed for key=Honda partition=1 offset=...: ConnectException: Connection refused
Gave up on key=Honda partition=1 offset=..., published it to cars-dlt
```

The exception can differ between attempts. For example, the first attempt may fail on a database connection that was open when the database went away, and the next ones because no new connection can be opened. They still count as one series of 3 attempts; by default, Spring Kafka would start counting again when the exception type changes (see `KafkaErrorHandlingConfig`).

Start the database again with `docker compose start postgres`. The Civic is in the dead-letter topic, not in the database: dead-lettered messages need to be reprocessed explicitly, for example with a replay as in scenario 3.

## Tests

```bash
./gradlew test
```

The tests are end-to-end: they start their own throwaway Kafka and PostgreSQL containers with Testcontainers, send cars through the REST API, and check the database and the dead-letter topic. One test makes the database fail on purpose, to check that a car is tried exactly 3 times even when the exception changes between attempts. Docker must be running.
