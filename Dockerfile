# syntax=docker/dockerfile:1

# Three stages, and only the last one becomes the image:
#   build    compiles the app with Gradle, so no JDK is needed on your machine
#   jre      links a Java runtime that contains only the JDK modules the app uses
#   runtime  Alpine Linux + that runtime + the app, running as a non-root user

# --- build -------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-alpine AS build
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

# List the JDK modules that the app and its libraries reference, e.g. java.sql for JDBC or
# java.desktop for java.beans (used by Spring). This runs on every build, so a new dependency
# that needs another module is picked up automatically.
RUN jdeps --print-module-deps --ignore-missing-deps --recursive --multi-release 25 \
        --class-path 'extracted/dependencies/lib/*:extracted/snapshot-dependencies/lib/*' \
        extracted/application/application.jar > jdk-modules.txt

# --- jre ---------------------------------------------------------------------------------------
# A stage of its own, so the runtime is only linked again when the module list changes, not on
# every code change.
FROM eclipse-temurin:25-jdk-alpine AS jre
COPY --from=build /workspace/jdk-modules.txt /tmp/

# jdeps only sees modules that code refers to directly. If a library loads a module by
# reflection and fails with ClassNotFoundException, add that module to --add-modules by hand.
# Left out on purpose, both measured for this app:
#   --compress              smaller on disk, but a bigger download: registries compress image
#                           layers anyway, and compressed data doesn't compress a second time
#   --generate-cds-archive  27 MB more, and the app didn't start any faster
RUN jlink --add-modules "$(cat /tmp/jdk-modules.txt)" \
        --strip-debug --no-man-pages --no-header-files \
        --output /jre

# --- runtime -----------------------------------------------------------------------------------
# The Alpine release the JDK image is based on: the linked runtime uses Alpine's C library (musl).
FROM alpine:3.24
ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre /jre ${JAVA_HOME}

WORKDIR /application
RUN addgroup -S spring && adduser -S -G spring spring
USER spring

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

EXPOSE 9001
# application.jar only contains the app's own classes and references the extracted libraries.
ENTRYPOINT ["java", "-jar", "application.jar"]
