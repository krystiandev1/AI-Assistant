package com.example.cdq.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * Unit tests for CosineSimilarity utility.
 * No Spring context, no Ollama required.
 * These verify the math before it is used in EmbeddingModelIT.
 */
class CosineSimilarityTest {

    @Test
    void identical_vectors_have_similarity_one() {
        float[] v = {1f, 0f, 0f};

        double result = CosineSimilarity.compute(v, v);

        assertThat(result).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void orthogonal_vectors_have_zero_similarity() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};

        double result = CosineSimilarity.compute(a, b);

        assertThat(result).isCloseTo(0.0, offset(1e-9));
    }

    @Test
    void antiparallel_vectors_have_negative_similarity() {
        float[] a = {1f, 0f};
        float[] b = {-1f, 0f};

        double result = CosineSimilarity.compute(a, b);

        assertThat(result).isCloseTo(-1.0, offset(1e-9));
    }

    @Test
    void mismatched_lengths_throw_exception() {
        float[] a = {1f, 0f};
        float[] b = {1f, 0f, 0f};

        assertThatThrownBy(() -> CosineSimilarity.compute(a, b))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2 vs 3");
    }

    @Test
    void zero_vector_returns_zero() {
        float[] zero = {0f, 0f, 0f};
        float[] nonZero = {1f, 0f, 0f};

        double result = CosineSimilarity.compute(zero, nonZero);

        assertThat(result).isEqualTo(0.0);
    }
}
