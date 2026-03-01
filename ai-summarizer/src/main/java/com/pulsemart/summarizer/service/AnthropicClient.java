package com.pulsemart.summarizer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class AnthropicClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public AnthropicClient(
            @Value("${anthropic.api-key}") String apiKey,
            @Value("${anthropic.model}") String model,
            ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    public SummaryResult summarize(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 1024,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                )
        );

        try {
            String responseJson = restClient.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            String text = root.path("content").get(0).path("text").asText();
            int inputTokens = root.path("usage").path("input_tokens").asInt();
            int outputTokens = root.path("usage").path("output_tokens").asInt();

            log.info("Anthropic API call succeeded: model={} inputTokens={} outputTokens={}",
                    model, inputTokens, outputTokens);

            return new SummaryResult(text, model, inputTokens, outputTokens);
        } catch (Exception e) {
            log.error("Anthropic API call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call Anthropic API", e);
        }
    }

    public record SummaryResult(String text, String model, int promptTokens, int completionTokens) {}
}
