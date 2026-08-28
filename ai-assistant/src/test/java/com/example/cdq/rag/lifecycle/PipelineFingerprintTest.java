package com.example.cdq.rag.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineFingerprintTest {

    private static final PipelineConfig BASE =
        new PipelineConfig("qwen3-embedding:0.6b", 1024, "v1");

    private String compute(String sourceHash, PipelineConfig cfg) {
        String input = sourceHash + "|" + cfg.embeddingModel() + "|"
            + cfg.embeddingDimensions() + "|" + cfg.processorVersion();
        return DocumentHasher.sha256Hex(input);
    }

    @Test
    void identical_inputs_produce_identical_fingerprint() {
        assertThat(compute("abc123", BASE)).isEqualTo(compute("abc123", BASE));
    }

    @Test
    void different_embedding_model_produces_different_fingerprint() {
        PipelineConfig other = new PipelineConfig("nomic-embed-text", 1024, "v1");
        assertThat(compute("abc123", BASE)).isNotEqualTo(compute("abc123", other));
    }

    @Test
    void different_embedding_dimensions_produces_different_fingerprint() {
        PipelineConfig other = new PipelineConfig("qwen3-embedding:0.6b", 768, "v1");
        assertThat(compute("abc123", BASE)).isNotEqualTo(compute("abc123", other));
    }

    @Test
    void different_processor_version_produces_different_fingerprint() {
        PipelineConfig other = new PipelineConfig("qwen3-embedding:0.6b", 1024, "v2");
        assertThat(compute("abc123", BASE)).isNotEqualTo(compute("abc123", other));
    }

    @Test
    void different_source_hash_produces_different_fingerprint() {
        assertThat(compute("hash-v1", BASE)).isNotEqualTo(compute("hash-v2", BASE));
    }
}
