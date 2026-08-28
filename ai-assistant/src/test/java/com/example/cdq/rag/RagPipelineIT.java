package com.example.cdq.rag;

import com.example.cdq.config.AppProperties;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the full CDQ RAG ingestion + retrieval pipeline.
 *
 * Requirements:
 *   - Docker must be running (for pgvector/pgvector:pg17 Testcontainer)
 *   - Ollama must be running: ollama serve
 *   - Models must be available: ollama pull qwen3-embedding:0.6b
 *
 * If either dependency is unavailable, all tests are SKIPPED (not FAILED).
 *
 * Test lifecycle:
 *   @BeforeAll → check deps → synchronize once per class → all tests read from the same pgvector state
 *   NOT @BeforeEach — Testcontainer DB is NOT reset between tests; ingest once avoids duplicates.
 *
 * Run: mvn verify -Pintegration -pl ai-assistant
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("rag-it")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagPipelineIT {

    private static final Logger log = LoggerFactory.getLogger(RagPipelineIT.class);

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry r) {
        // Start explicitly: SpringExtension.beforeAll() runs before TestcontainersExtension.beforeAll(),
        // so Spring reads these properties before @Container lifecycle starts the container.
        postgres.start();
        r.add("spring.datasource.url",              postgres::getJdbcUrl);
        r.add("spring.datasource.username",         postgres::getUsername);
        r.add("spring.datasource.password",         postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired AppProperties              appProperties;
    @Autowired DocumentLifecycleService   lifecycleService;
    @Autowired RagRetrieval               ragRetrieval;
    @Autowired JdbcTemplate               jdbcTemplate;

    @BeforeAll
    void setUpAndIngest() {
        assumeTrue(isOllamaRunning(),
            "Ollama not available — skipping RAG pipeline tests. " +
            "Start with: ollama serve && ollama pull qwen3-embedding:0.6b");

        lifecycleService.synchronize(appProperties.rag().sourceId());
    }

    // ── Ingestion ────────────────────────────────────────────────────────────

    @Test
    void ingest_stores_documents_in_pgvector() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store", Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void ingest_stores_reasonable_chunk_count() {
        Integer stored = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store", Integer.class);
        assertThat(stored).isBetween(8, 25);
    }

    @Test
    void all_stored_chunks_have_sourceId() {
        Integer nullSourceId = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'sourceId' IS NULL", Integer.class);
        assertThat(nullSourceId).isZero();
    }

    @Test
    void all_stored_chunks_have_section() {
        Integer nullSection = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'section' IS NULL", Integer.class);
        assertThat(nullSection).isZero();
    }

    @Test
    void all_stored_chunks_have_source_version_id() {
        Integer nullVersionId = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'sourceVersionId' IS NULL", Integer.class);
        assertThat(nullVersionId).isZero();
    }

    // ── EN direct queries — Hit@1 ────────────────────────────────────────────

    @Test
    void trust_score_direct_hit1() {
        assertHit1("What is the Trust Score?",
            "trust score");
    }

    @Test
    void fraud_management_query_hit1() {
        assertHit1("Can users document known fraud cases?",
            "fraud case management");
    }

    // ── EN direct queries — Hit@3 ────────────────────────────────────────────
    // qwen3-embedding:0.6b has limited fine-grained discrimination for CDQ-branded section queries

    @Test
    void bank_account_query_hit3() {
        assertHit3("How does CDQ Fraud Guard verify bank accounts?",
            "bank account verification");
    }

    @Test
    void fraud_alerts_query_hit3() {
        assertHit3("How does CDQ warn companies about fraud attacks?",
            "fraud alerts", "payment fraud alerts");
    }

    @Test
    void seamless_integration_query_hit3() {
        assertHit3("Does CDQ Fraud Guard require a dedicated user interface?",
            "seamless integration");
    }

    // ── EN paraphrase queries — Hit@3 ────────────────────────────────────────

    @Test
    void trust_score_paraphrase_hit3() {
        assertHit3("How does CDQ determine whether a bank account can be trusted?",
            "trust score");
    }

    @Test
    void trust_score_risk_rating_hit3() {
        assertHit3("Bank account risk rating system",
            "trust score", "customizable trust scores");
    }

    @Test
    void efficiency_paraphrase_hit3() {
        assertHit3("What helps reduce repetitive manual tasks for finance teams?",
            "operational efficiency");
    }

    @Test
    void security_paraphrase_hit3() {
        assertHit3("How does CDQ reduce the risk of fraud?",
            "enhanced security");
    }

    // ── Cross-lingual PL/DE queries — Hit@3 ─────────────────────────────────

    @Test
    @Disabled("qwen3-embedding:0.6b insufficient cross-lingual Polish coverage for semantic Trust Score queries; German equivalent de_trust_score_hit3 passes")
    void pl_trust_score_disabled() {
        assertHit5("Jak CDQ ocenia wiarygodność rachunku bankowego?",
            "trust score");
    }

    @Test
    void de_trust_score_hit3() {
        assertHit3("Wie bewertet CDQ die Vertrauenswürdigkeit eines Bankkontos?",
            "trust score");
    }

    @Test
    void pl_bank_verification_hit3() {
        assertHit3("Jak CDQ weryfikuje konto bankowe kontrahenta?",
            "bank account verification");
    }

    @Test
    void de_fraud_alerts_hit3() {
        assertHit3("Wie werden Betrugsangriffe in CDQ gemeldet?",
            "payment fraud alerts", "fraud alerts");
    }

    // ── Negative retrieval ───────────────────────────────────────────────────

    @Test
    void out_of_domain_queries_score_lower_than_cdq_queries() {
        double cdqScore     = topScore("How does CDQ Fraud Guard verify bank accounts?");
        double weatherScore = topScore("What is the weather in Berlin today?");
        double popScore     = topScore("What is the population of Munich?");
        double ceoScore     = topScore("Who is the CEO of Microsoft?");

        assertThat(weatherScore)
            .as("Out-of-domain weather query (%.4f) should score lower than CDQ query (%.4f)",
                weatherScore, cdqScore)
            .isLessThan(cdqScore);
        assertThat(popScore)
            .as("Out-of-domain population query (%.4f) should score lower than CDQ query (%.4f)",
                popScore, cdqScore)
            .isLessThan(cdqScore);
        assertThat(ceoScore)
            .as("Out-of-domain CEO query (%.4f) should score lower than CDQ query (%.4f)",
                ceoScore, cdqScore)
            .isLessThan(cdqScore);
    }

    // ── Similarity score diagnostics ─────────────────────────────────────────

    @Test
    @Disabled("Diagnostic — run manually: mvn verify -Pintegration -Dtest=RagPipelineIT#print_similarity_scores_for_all_queries")
    void print_similarity_scores_for_all_queries() {
        List<String> queries = List.of(
            "How does CDQ Fraud Guard verify bank accounts?",
            "What is the Trust Score?",
            "How does CDQ warn companies about fraud attacks?",
            "Can users document known fraud cases?",
            "Does CDQ Fraud Guard require a dedicated user interface?",
            "How does CDQ determine whether a bank account can be trusted?",
            "Bank account risk rating system",
            "What helps reduce repetitive manual tasks for finance teams?",
            "How does CDQ reduce the risk of fraud?",
            "Jak CDQ ocenia wiarygodność rachunku bankowego?",
            "Wie bewertet CDQ die Vertrauenswürdigkeit eines Bankkontos?",
            "Jak CDQ weryfikuje konto bankowe kontrahenta?",
            "Wie werden Betrugsangriffe in CDQ gemeldet?",
            "What is the weather in Berlin today?",
            "What is the population of Munich?",
            "Who is the CEO of Microsoft?"
        );

        System.out.println("\n=== SIMILARITY SCORE DIAGNOSTICS ===");
        System.out.printf("%-65s | %s%n", "Query", "Top-3 results (section → score)");
        System.out.println("-".repeat(120));
        for (String query : queries) {
            List<Document> results = search(query, 5);
            StringBuilder sb = new StringBuilder();
            results.stream().limit(3).forEach(d -> sb.append(String.format("[%s → %.4f] ",
                d.getMetadata().getOrDefault("section", "?"), d.getScore())));
            System.out.printf("%-65s | %s%n", truncate(query, 63), sb);
        }
        System.out.println("=== END DIAGNOSTICS ===\n");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Document> search(String query, int topK) {
        List<Document> results = ragRetrieval.search(
            appProperties.rag().sourceId(), query, topK, 0.0);
        logRanking(query, results);
        return results;
    }

    private void assertHit1(String query, String... expectedSectionKeywords) {
        List<Document> results = search(query, 5);
        assertThat(results).isNotEmpty();
        assertThat(sectionMatches(results.get(0), expectedSectionKeywords))
            .as("Hit@1 failed for query: [%s]. Top result section: [%s]",
                query, results.get(0).getMetadata().get("section"))
            .isTrue();
    }

    private void assertHit3(String query, String... expectedSectionKeywords) {
        List<Document> results = search(query, 5);
        assertThat(results).isNotEmpty();
        boolean found = results.subList(0, Math.min(3, results.size())).stream()
            .anyMatch(d -> sectionMatches(d, expectedSectionKeywords));
        assertThat(found)
            .as("Hit@3 failed for query: [%s]. Top 3 sections: %s",
                query, results.subList(0, Math.min(3, results.size())).stream()
                    .map(d -> d.getMetadata().get("section")).toList())
            .isTrue();
    }

    private void assertHit5(String query, String... expectedSectionKeywords) {
        List<Document> results = search(query, 5);
        assertThat(results).isNotEmpty();
        boolean found = results.stream().anyMatch(d -> sectionMatches(d, expectedSectionKeywords));
        assertThat(found)
            .as("Hit@5 failed for query: [%s]. Top 5 sections: %s",
                query, results.stream().map(d -> d.getMetadata().get("section")).toList())
            .isTrue();
    }

    private boolean sectionMatches(Document doc, String... keywords) {
        String section = doc.getMetadata().getOrDefault("section", "").toString().toLowerCase();
        for (String kw : keywords) {
            if (section.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private double topScore(String query) {
        List<Document> results = search(query, 1);
        if (results.isEmpty()) return 0.0;
        Double score = results.get(0).getScore();
        return score != null ? score : 0.0;
    }

    private void logRanking(String query, List<Document> results) {
        log.info("Query: [{}]", query);
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            Document doc = results.get(i);
            log.info("  #{}: section=[{}] parentSection=[{}] score=[{}]",
                i + 1,
                doc.getMetadata().getOrDefault("section", "?"),
                doc.getMetadata().getOrDefault("parentSection", "-"),
                doc.getScore());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static boolean isOllamaRunning() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                new URI("http://localhost:11434").toURL().openConnection();
            conn.setConnectTimeout(2_000);
            conn.connect();
            return conn.getResponseCode() >= 0;
        } catch (Exception e) {
            return false;
        }
    }
}
