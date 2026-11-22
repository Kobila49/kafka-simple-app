# syntax=docker/dockerfile:1

# --- Build stage: compile the app with Gradle, so no JDK is needed on the host ---------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src

# The cache mount keeps the Gradle distribution and dependencies between builds.
# Tests are skipped here: they start their own containers (Testcontainers) and run with
# ./gradlew test on the host instead.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon --console=plain

# Split the jar into layers: dependencies change rarely, application code changes often, so
# Docker can reuse the dependency layers on rebuilds.
RUN cp build/libs/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- Runtime stage: just a JRE and the extracted app ------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /application

RUN groupadd --system spring && useradd --system --gid spring spring
USER spring

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

EXPOSE 9001
# application.jar only contains the app's own classes and references the extracted libraries.
ENTRYPOINT ["java", "-jar", "application.jar"]
