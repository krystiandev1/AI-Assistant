package com.example.cdq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
    Rag rag
) {
    public record Rag(
        double similarityThreshold,
        int embeddingExpectedDimensions,
        String sourceId,
        String sourceUrl,
        String resourcePath
    ) {}
}
