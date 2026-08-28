package com.example.cdq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app")
public record AppProperties(
    Countries countries,
    Timeouts timeouts,
    Rag rag,
    Weather weather
) {
    public record Countries(String baseUrl, String apiKey) {}

    public record Timeouts(Duration countries, Duration weatherMcp) {}

    public record Rag(double similarityThreshold, int embeddingExpectedDimensions) {}

    public record Weather(String apiKey, String mcpPath) {}
}
