FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
COPY shared-lib shared-lib
COPY inventory-service inventory-service
RUN ./gradlew :inventory-service:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# OpenTelemetry Java agent for auto-instrumentation
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v1.32.0/opentelemetry-javaagent.jar /app/otel-agent.jar

COPY --from=builder /app/inventory-service/build/libs/*.jar app.jar

ENV JAVA_TOOL_OPTIONS="-javaagent:/app/otel-agent.jar"

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
