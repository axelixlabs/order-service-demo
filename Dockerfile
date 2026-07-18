# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM gradle:9.1.0-jdk25 AS build
WORKDIR /workspace

# Warm the dependency cache first for faster incremental builds.
COPY settings.gradle build.gradle ./
COPY gradle ./gradle
RUN gradle --no-daemon dependencies || true

# Build the executable jar (skip tests here; run them in CI).
COPY src ./src
RUN gradle --no-daemon clean bootJar -x test

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN groupadd --system app && useradd --system --gid app app
USER app

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
