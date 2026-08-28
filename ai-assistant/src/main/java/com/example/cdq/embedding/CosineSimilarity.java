package com.example.cdq.embedding;

public final class CosineSimilarity {

    private CosineSimilarity() {}

    /**
     * Computes cosine similarity between two float vectors.
     * Returns a value in [-1.0, 1.0]: 1.0 = identical direction, 0.0 = orthogonal, -1.0 = opposite.
     * For L2-normalized vectors (e.g. qwen3-embedding output), this equals the dot product.
     */
    public static double compute(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Vectors must have equal length: %d vs %d".formatted(a.length, b.length));
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) {
            return 0.0;
        }
        return dot / denominator;
    }
}
