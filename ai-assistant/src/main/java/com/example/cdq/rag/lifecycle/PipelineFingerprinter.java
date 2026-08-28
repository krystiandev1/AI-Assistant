package com.example.cdq.rag.lifecycle;

import org.springframework.stereotype.Component;

/**
 * Computes the pipeline fingerprint — a SHA-256 over all factors that determine embedding output.
 * A matching ACTIVE fingerprint means the stored chunks are identical to what would be produced now,
 * so ingestion can be safely skipped.
 */
@Component
public class PipelineFingerprinter {

    public String compute(String sourceHash, PipelineConfig config) {
        String input = sourceHash
            + "|" + config.embeddingModel()
            + "|" + config.embeddingDimensions()
            + "|" + config.processorVersion();
        return DocumentHasher.sha256Hex(input);
    }
}
