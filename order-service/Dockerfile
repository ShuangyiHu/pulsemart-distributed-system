# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /workspace

# Copy Gradle wrapper and build files first (layer caching)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY shared-lib/build.gradle shared-lib/build.gradle
COPY order-service/build.gradle order-service/build.gradle

# Download dependencies (cached layer unless build.gradle changes)
RUN ./gradlew :order-service:dependencies --no-daemon -q 2>/dev/null || true

# Copy source and build
COPY shared-lib/src shared-lib/src
COPY order-service/src order-service/src
RUN ./gradlew :order-service:bootJar --no-daemon -x test

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Download OpenTelemetry Java agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar /app/otel-agent.jar

COPY --from=builder /workspace/order-service/build/libs/*.jar app.jar

EXPOSE 8081

ENV JAVA_TOOL_OPTIONS="-javaagent:/app/otel-agent.jar"
ENV OTEL_SERVICE_NAME="order-service"
ENV OTEL_EXPORTER_OTLP_ENDPOINT="http://jaeger:4318"
ENV OTEL_METRICS_EXPORTER="none"
ENV OTEL_LOGS_EXPORTER="none"

ENTRYPOINT ["java", "-jar", "app.jar"]
