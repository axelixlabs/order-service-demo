# syntax=docker/dockerfile:1

# ---- Build stage: compile to an executable jar ----
FROM gradle:9.1.0-jdk25 AS build
WORKDIR /workspace

# Warm the dependency cache first for faster incremental builds.
COPY settings.gradle build.gradle ./
COPY gradle ./gradle
RUN gradle --no-daemon dependencies || true

# Build the executable jar (skip tests here; run them in CI).
COPY src ./src
RUN gradle --no-daemon clean bootJar -x test

# ---- Layers + AOT stage: split the jar into Spring Boot layers and build the AOT cache ----
# Same base image as the runtime so the AOT cache is produced by the exact JVM
# that will later consume it.
FROM eclipse-temurin:25-jre AS layers
WORKDIR /build
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar app.jar

# Explode the fat jar into per-layer directories (-Djarmode=tools ... extract).
# These layers change at different rates, which keeps Docker's image cache warm.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# Overlay the layers into /app — the SAME absolute path used at runtime — so the
# classpath recorded in the AOT cache matches when the container runs.
RUN mkdir -p /app \
    && cp -r extracted/dependencies/.          /app/ \
    && cp -r extracted/spring-boot-loader/.     /app/ \
    && cp -r extracted/snapshot-dependencies/.  /app/ \
    && cp -r extracted/application/.            /app/

# Project Leyden AOT cache. A single training run (JEP 514 ergonomics:
# -XX:AOTCacheOutput records the config and assembles the cache in one step)
# boots the Spring context and exits on refresh. External systems are neutralised
# so the run needs no broker or database:
#   - context.exit=onRefresh          exit right after the context refreshes
#   - flyway.enabled=false            skip DB migrations
#   - ddl-auto=none + explicit dialect + no jdbc-metadata  build the EMF offline
#   - hikari initialization-fail-timeout=-1  don't open a DB connection at startup
#   - kafka listener auto-startup=false + auto-create-topics=false  don't reach the broker
# UseCompactObjectHeaders MUST match the runtime flags or the cache is rejected.
WORKDIR /app
RUN java -XX:AOTCacheOutput=app.aot -XX:+UseCompactObjectHeaders -cp . \
        -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false \
        -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
        -Dspring.kafka.listener.auto-startup=false \
        -Dapp.kafka.auto-create-topics=false \
        -Dapp.seed.enabled=false \
        org.springframework.boot.loader.launch.JarLauncher

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN groupadd --system app && useradd --system --gid app app

# Copy each layer separately, least-to-most frequently changed, so a code-only
# change re-uses the cached dependency layers. Finally add the AOT cache built
# from exactly this application.
COPY --from=layers --chown=app:app /build/extracted/dependencies/         ./
COPY --from=layers --chown=app:app /build/extracted/spring-boot-loader/    ./
COPY --from=layers --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=app:app /build/extracted/application/           ./
COPY --from=layers --chown=app:app /app/app.aot                            ./app.aot

USER app
EXPOSE 8080

# Extra JVM tuning (e.g. heap) comes from JAVA_OPTS; the two flags below are the
# point of this image and are always applied:
#   -XX:AOTCache                  Project Leyden ahead-of-time class loading & linking
#   -XX:+UseCompactObjectHeaders  Project Lilliput 8-byte object headers
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java -XX:AOTCache=app.aot -XX:+UseCompactObjectHeaders $JAVA_OPTS -cp . org.springframework.boot.loader.launch.JarLauncher"]
