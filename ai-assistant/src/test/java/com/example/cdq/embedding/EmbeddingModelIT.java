package com.example.cdq.embedding;

import com.example.cdq.config.AppProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.net.HttpURLConnection;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the EmbeddingModel layer (Phase 1 contract).
 *
 * These tests verify the contract:
 *   String text → Spring AI EmbeddingModel → Ollama → qwen3-embedding:0.6b → float[1024]
 *
 * Requirements:
 *   - Ollama must be running: ollama serve
 *   - Model must be available: ollama pull qwen3-embedding:0.6b
 *
 * If Ollama is not reachable all tests are SKIPPED (not FAILED) — CI is not blocked.
 *
 * Run: mvn verify -Pintegration -pl ai-assistant
 */
@SpringBootTest
@ActiveProfiles("integration")
@Tag("integration")
class EmbeddingModelIT {

    private static final String CDQ_SENTENCE = "CDQ Fraud Guard verifies bank accounts.";
    private static final String SIMILAR_EN   = "The service checks bank account information.";
    private static final String SIMILAR_PL   = "Usługa weryfikuje dane konta bankowego.";
    private static final String SIMILAR_DE   = "Der Dienst überprüft Bankkontoinformationen.";
    private static final String UNRELATED    = "Munich is a city in Germany.";

    // Prevents PgVectorStore auto-configuration from attempting a real PostgreSQL connection
    // during Phase 1 tests. Only EmbeddingModel + Ollama are under test here.
    @MockitoBean VectorStore vectorStore;

    @Autowired EmbeddingModel embeddingModel;
    @Autowired AppProperties  appProperties;

    @BeforeAll
    static void checkOllamaAvailability() {
        boolean running = false;
        try {
            HttpURLConnection conn = (HttpURLConnection)
                new URI("http://localhost:11434").toURL().openConnection();
            conn.setConnectTimeout(2_000);
            conn.connect();
            running = conn.getResponseCode() >= 0;
        } catch (Exception ignored) {}
        assumeTrue(running,
            "Ollama not running — skipping embedding integration tests. " +
            "Start it with: ollama serve && ollama pull qwen3-embedding:0.6b");
    }

    /**
     * Test 1 — Smoke test: embedding is generated and is not empty.
     * Verifies that Ollama is reachable and Spring AI deserializes the response correctly.
     */
    @Test
    void generates_non_null_non_empty_embedding() {
        float[] embedding = embed(CDQ_SENTENCE);

        assertThat(embedding).isNotNull().isNotEmpty();
    }

    /**
     * Test 2 — Dimension contract: embedding length equals the configured expected dimensions.
     * This is the pgvector compatibility contract: if the model is swapped without updating
     * app.rag.embedding-expected-dimensions, this test fails before the schema becomes corrupt.
     */
    @Test
    void embedding_has_expected_dimension() {
        int expectedDimensions = appProperties.rag().embeddingExpectedDimensions();
        float[] embedding = embed(CDQ_SENTENCE);

        assertThat(embedding).hasSize(expectedDimensions);
    }

    /**
     * Test 3 — Numeric validity: every value must be a finite float.
     * Catches NaN, Infinity, or -Infinity that would silently corrupt pgvector distance calculations.
     */
    @Test
    void all_values_are_finite() {
        float[] embedding = embed(CDQ_SENTENCE);

        for (float value : embedding) {
            assertThat(Float.isFinite(value))
                .as("Expected finite value but got: %f", value)
                .isTrue();
        }
    }

    /**
     * Test 4 — L2 normalization: the vector's Euclidean norm must be close to 1.0.
     * qwen3-embedding:0.6b produces L2-normalized vectors (confirmed in Qwen3 documentation).
     * This property ensures cosine similarity in pgvector (COSINE_DISTANCE) yields correct results.
     */
    @Test
    void embedding_is_l2_normalized() {
        float[] embedding = embed(CDQ_SENTENCE);

        double norm = computeL2Norm(embedding);

        assertThat(norm).isCloseTo(1.0, offset(0.0001));
    }

    /**
     * Test 5 — Semantic similarity EN/EN: semantically related sentences are closer than unrelated ones.
     * Tests the fundamental retrieval property: the model must preserve semantic relationships.
     * No arbitrary threshold is asserted — the ordering relationship is a stable contract
     * that survives model version changes as long as the model is semantically correct.
     */
    @Test
    void semantically_similar_sentences_are_closer_than_unrelated() {
        float[] a = embed(CDQ_SENTENCE);
        float[] b = embed(SIMILAR_EN);
        float[] c = embed(UNRELATED);

        double simAB = CosineSimilarity.compute(a, b);
        double simAC = CosineSimilarity.compute(a, c);

        assertThat(simAB)
            .as("sim(CDQ sentence, similar EN) should be > sim(CDQ sentence, unrelated)")
            .isGreaterThan(simAC);
    }

    /**
     * Test 6 — Cross-lingual retrieval: Polish and German queries must be semantically closer
     * to the English document than an unrelated English sentence.
     * This directly validates the key differentiator of qwen3-embedding:0.6b over EN-only models.
     * If this model is replaced with a monolingual alternative, these assertions will fail —
     * signalling that multilingual RAG capability has been lost.
     */
    @Test
    void cross_lingual_queries_are_closer_to_english_document_than_unrelated_text() {
        float[] enDoc     = embed(CDQ_SENTENCE);
        float[] plQuery   = embed(SIMILAR_PL);
        float[] deQuery   = embed(SIMILAR_DE);
        float[] unrelated = embed(UNRELATED);

        double simPl = CosineSimilarity.compute(enDoc, plQuery);
        double simDe = CosineSimilarity.compute(enDoc, deQuery);
        double simC  = CosineSimilarity.compute(enDoc, unrelated);

        assertThat(simPl)
            .as("sim(EN doc, PL query) should be > sim(EN doc, unrelated EN)")
            .isGreaterThan(simC);
        assertThat(simDe)
            .as("sim(EN doc, DE query) should be > sim(EN doc, unrelated EN)")
            .isGreaterThan(simC);
    }

    /**
     * Test 7 — Stability: the same input must produce directionally stable embeddings.
     * Ollama with Q8_0 quantization does not guarantee bit-for-bit identical outputs across
     * repeated calls (minor floating-point variance is possible). We therefore measure
     * cosine similarity between the two results — for a stable model it must be ≥ 1 - 1e-4,
     * meaning the semantic direction is preserved even if individual float values differ slightly.
     */
    @Test
    void same_input_produces_stable_embedding() {
        float[] first  = embed(CDQ_SENTENCE);
        float[] second = embed(CDQ_SENTENCE);

        double similarity = CosineSimilarity.compute(first, second);

        assertThat(similarity)
            .as("Embedding for the same text should be directionally stable across repeated calls " +
                "(cosine similarity must be close to 1.0, got: %f)", similarity)
            .isCloseTo(1.0, offset(1e-4));
    }

    private float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    private static double computeL2Norm(float[] v) {
        double sumOfSquares = 0.0;
        for (float x : v) {
            sumOfSquares += (double) x * x;
        }
        return Math.sqrt(sumOfSquares);
    }
}
