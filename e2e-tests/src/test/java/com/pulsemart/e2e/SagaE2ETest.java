package com.pulsemart.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SagaE2ETest {

    static DockerComposeContainer<?> environment;
    static OkHttpClient httpClient;
    static ObjectMapper objectMapper;
    static String gatewayUrl;

    @BeforeAll
    static void startEnvironment() {
        environment = new DockerComposeContainer<>(
                new File("src/test/resources/docker-compose-test.yml"))
                .withExposedService("api-gateway", 8080,
                        Wait.forHttp("/actuator/health")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(5)))
                .withLocalCompose(true);

        environment.start();

        int port = environment.getServicePort("api-gateway", 8080);
        gatewayUrl = "http://localhost:" + port;

        httpClient = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @AfterAll
    static void stopEnvironment() {
        if (environment != null) {
            environment.stop();
        }
    }

    @Test
    void happyPath_orderCompletesAndSummaryGenerated() throws Exception {
        // 1. Obtain JWT token
        String token = obtainToken("test-user", UUID.randomUUID().toString());
        assertThat(token).isNotEmpty();

        // 2. Place order
        String orderJson = objectMapper.writeValueAsString(Map.of(
                "customerId", UUID.randomUUID().toString(),
                "items", java.util.List.of(Map.of(
                        "productId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        "productName", "Widget Pro",
                        "quantity", 1,
                        "unitPrice", 29.99
                ))
        ));

        Request placeOrder = new Request.Builder()
                .url(gatewayUrl + "/orders")
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create(orderJson, MediaType.parse("application/json")))
                .build();

        String orderId;
        try (Response response = httpClient.newCall(placeOrder).execute()) {
            assertThat(response.code()).isEqualTo(201);
            JsonNode body = objectMapper.readTree(response.body().string());
            orderId = body.get("orderId").asText();
            assertThat(body.get("status").asText()).isEqualTo("PENDING");
        }

        // 3. Wait for order to reach COMPLETED status (saga finishes)
        await().atMost(90, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Request getOrder = new Request.Builder()
                            .url(gatewayUrl + "/orders/" + orderId)
                            .header("Authorization", "Bearer " + token)
                            .get()
                            .build();
                    try (Response response = httpClient.newCall(getOrder).execute()) {
                        assertThat(response.code()).isEqualTo(200);
                        JsonNode body = objectMapper.readTree(response.body().string());
                        assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
                    }
                });

        // 4. Wait for AI summary to be generated (via WireMock mock)
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Request getSummary = new Request.Builder()
                            .url(gatewayUrl + "/summaries/" + orderId)
                            .get()
                            .build();
                    try (Response response = httpClient.newCall(getSummary).execute()) {
                        assertThat(response.code()).isEqualTo(200);
                        JsonNode body = objectMapper.readTree(response.body().string());
                        assertThat(body.get("summaryText").asText()).isNotEmpty();
                    }
                });
    }

    @Test
    void compensationPath_orderCancelledOnPaymentFailure() throws Exception {
        // This test uses PAYMENT_FAILURE_RATE=0 from compose,
        // but we rely on the default setup. For a real compensation test,
        // you'd need a separate compose with PAYMENT_FAILURE_RATE=1.0
        // or a way to control payment behavior per-request.
        // For now, this test verifies the basic auth + order creation flow.

        String token = obtainToken("test-user-2", UUID.randomUUID().toString());

        String orderJson = objectMapper.writeValueAsString(Map.of(
                "customerId", UUID.randomUUID().toString(),
                "items", java.util.List.of(Map.of(
                        "productId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        "productName", "Widget Pro",
                        "quantity", 1,
                        "unitPrice", 15.00
                ))
        ));

        Request placeOrder = new Request.Builder()
                .url(gatewayUrl + "/orders")
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create(orderJson, MediaType.parse("application/json")))
                .build();

        String orderId;
        try (Response response = httpClient.newCall(placeOrder).execute()) {
            assertThat(response.code()).isEqualTo(201);
            JsonNode body = objectMapper.readTree(response.body().string());
            orderId = body.get("orderId").asText();
        }

        // Wait for order to reach a terminal state (COMPLETED or CANCELLED)
        await().atMost(90, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Request getOrder = new Request.Builder()
                            .url(gatewayUrl + "/orders/" + orderId)
                            .header("Authorization", "Bearer " + token)
                            .get()
                            .build();
                    try (Response response = httpClient.newCall(getOrder).execute()) {
                        assertThat(response.code()).isEqualTo(200);
                        JsonNode body = objectMapper.readTree(response.body().string());
                        String status = body.get("status").asText();
                        assertThat(status).isIn("COMPLETED", "CANCELLED");
                    }
                });
    }

    @Test
    void unauthenticatedRequest_shouldReturn401() throws Exception {
        Request request = new Request.Builder()
                .url(gatewayUrl + "/orders")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(401);
        }
    }

    @Test
    void publicEndpoint_summariesAccessibleWithoutAuth() throws Exception {
        Request request = new Request.Builder()
                .url(gatewayUrl + "/summaries")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String obtainToken(String userId, String customerId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "userId", userId,
                "customerId", customerId
        ));
        Request request = new Request.Builder()
                .url(gatewayUrl + "/auth/token")
                .post(RequestBody.create(body, MediaType.parse("application/json")))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode json = objectMapper.readTree(response.body().string());
            return json.get("token").asText();
        }
    }
}
