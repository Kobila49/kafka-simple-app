# syntax=docker/dockerfile:1

# Builds the app inside Docker, so no JDK is needed on your machine.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src

# The cache mount keeps Gradle's downloads between builds. The tests need Docker themselves, so
# they run with ./gradlew test on the host instead.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon --console=plain

# Dependencies change rarely, so they get image layers of their own. application.jar is then a
# thin jar that refers to them.
RUN cp build/libs/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Recomputed on every build, so a dependency that needs another JDK module is picked up.
RUN jdeps --print-module-deps --ignore-missing-deps --recursive --multi-release 25 \
        --class-path 'extracted/dependencies/lib/*:extracted/snapshot-dependencies/lib/*' \
        extracted/application/application.jar > jdk-modules.txt

# A Java runtime with only those modules. It's a stage of its own, so it's only linked again when
# the module list changes.
FROM eclipse-temurin:25-jdk-alpine AS jre
COPY --from=build /workspace/jdk-modules.txt /tmp/

# jdeps can't see modules that are only loaded by reflection: add those to --add-modules by hand.
# Measured and left out: --compress (a bigger download, because registries compress layers anyway)
# and --generate-cds-archive (27 MB more, no faster startup).
RUN jlink --add-modules "$(cat /tmp/jdk-modules.txt)" \
        --strip-debug --no-man-pages --no-header-files \
        --output /jre

# The Alpine release the JDK image is based on: the linked runtime uses its C library (musl).
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
ENTRYPOINT ["java", "-jar", "application.jar"]
