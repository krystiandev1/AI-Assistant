package com.example.cdq.rag.lifecycle;

public record RagSource(
    long   id,
    String sourceKey,
    String sourceUrl,
    Long   activeVersionId   // nullable
) {}
