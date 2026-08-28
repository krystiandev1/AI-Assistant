package com.example.cdq.rag.lifecycle;

import java.time.OffsetDateTime;

public record RagSourceVersion(
    long            id,
    long            sourceId,
    String          sourceHash,
    String          pipelineFingerprint,
    VersionStatus   status,
    String          embeddingModel,
    int             embeddingDimensions,
    String          processorVersion,
    Integer         chunkCount,          // null during PROCESSING
    OffsetDateTime  createdAt,
    OffsetDateTime  activatedAt,         // null until activation
    String          failureReason        // null unless FAILED
) {}
