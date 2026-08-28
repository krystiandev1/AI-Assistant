package com.example.cdq.rag.lifecycle;

public record PipelineConfig(
    String embeddingModel,
    int    embeddingDimensions,
    String processorVersion
) {}
