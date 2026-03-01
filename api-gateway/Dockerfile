# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace

# Copy Gradle wrapper and build files first (layer caching)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY api-gateway/build.gradle api-gateway/build.gradle

# Download dependencies (cached layer unless build.gradle changes)
RUN ./gradlew :api-gateway:dependencies --no-daemon -q 2>/dev/null || true

# Copy source and build
COPY api-gateway/src api-gateway/src
RUN ./gradlew :api-gateway:bootJar --no-daemon -x test

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# Download OpenTelemetry Java agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar /app/otel-agent.jar

COPY --from=builder /workspace/api-gateway/build/libs/*.jar app.jar

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-javaagent:/app/otel-agent.jar"
ENV OTEL_SERVICE_NAME="api-gateway"
ENV OTEL_EXPORTER_OTLP_ENDPOINT="http://jaeger:4318"
ENV OTEL_METRICS_EXPORTER="none"
ENV OTEL_LOGS_EXPORTER="none"

ENTRYPOINT ["java", "-jar", "app.jar"]
