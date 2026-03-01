# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY shared-lib/build.gradle shared-lib/build.gradle
COPY ai-summarizer/build.gradle ai-summarizer/build.gradle

RUN ./gradlew :ai-summarizer:dependencies --no-daemon -q 2>/dev/null || true

COPY shared-lib/src shared-lib/src
COPY ai-summarizer/src ai-summarizer/src
RUN ./gradlew :ai-summarizer:bootJar --no-daemon -x test

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.3.0/opentelemetry-javaagent.jar /app/otel-agent.jar

COPY --from=builder /workspace/ai-summarizer/build/libs/*.jar app.jar

EXPOSE 8088

ENV JAVA_TOOL_OPTIONS="-javaagent:/app/otel-agent.jar"
ENV OTEL_SERVICE_NAME="ai-summarizer"
ENV OTEL_EXPORTER_OTLP_ENDPOINT="http://jaeger:4318"
ENV OTEL_METRICS_EXPORTER="none"
ENV OTEL_LOGS_EXPORTER="none"

ENTRYPOINT ["java", "-jar", "app.jar"]
