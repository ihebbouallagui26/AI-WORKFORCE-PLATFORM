# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------- build
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /workspace

# Dependencies first: this layer is reused as long as pom.xml does not change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Tests run in CI and locally (Testcontainers needs Docker); the image build only packages.
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/core-api-*.jar extract --layers --launcher --destination extracted

# -------------------------------------------------------------------------- runtime
FROM eclipse-temurin:21.0.12.1_1-jre-noble

# Unprivileged runtime user; application files stay owned by root (read-only for the app).
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
# Spring Boot layers, from least to most frequently changing.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app:app
EXPOSE 8080

# Container-aware memory sizing; crash fast on OOM so the orchestrator restarts the container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SERVER_PORT=8080

# Flyway migrations run inside the application at startup (validated, versioned), never
# from an entrypoint script. Database readiness is handled by compose `depends_on` and by
# the connection pool timeout.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
