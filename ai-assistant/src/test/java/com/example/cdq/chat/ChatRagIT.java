package com.example.cdq.chat;

import com.example.cdq.config.AppProperties;
import com.example.cdq.evidence.ExecutionEvidence;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests: question → RAG (active-version filtered) → qwen3:4b → answer + evidence.
 *
 * Requirements: Docker + Ollama (qwen3:4b + qwen3-embedding:0.6b)
 * Profile: chat-it — MCP excluded, startup-sync=false, pgvector Testcontainer
 *
 * Ingestion runs once in @BeforeAll; all tests share the same DB state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("chat-it")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatRagIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry r) {
        postgres.start();
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",           postgres::getUsername);
        r.add("spring.datasource.password",           postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired AssistantService         assistantService;
    @Autowired DocumentLifecycleService lifecycleService;
    @Autowired AppProperties            appProperties;
    @Autowired JdbcTemplate             jdbcTemplate;

    @BeforeAll
    void setup() {
        assumeTrue(isOllamaRunning(),
            "Ollama not running — skipping ChatRagIT. Start: ollama serve");
        assumeTrue(isModelAvailable("qwen3:4b"),
            "qwen3:4b not pulled — skipping ChatRagIT. Run: ollama pull qwen3:4b");
        assumeTrue(isModelAvailable("qwen3-embedding:0.6b"),
            "qwen3-embedding:0.6b not pulled — skipping ChatRagIT. Run: ollama pull qwen3-embedding:0.6b");
        lifecycleService.synchronize(appProperties.rag().sourceId());
    }

    // ── Group A: EN direct ────────────────────────────────────────────────────

    @Test
    void en_trust_score() {
        ChatApiResponse r = ask("What is the Trust Score in CDQ Fraud Guard?");
        assertThat(r.answer()).isNotBlank();
        assertEvidenceContainsSection(r.evidence(), "trust score");
    }

    @Test
    void en_bank_account_verification() {
        ChatApiResponse r = ask("How does CDQ Fraud Guard verify bank accounts?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.answer().toLowerCase()).containsAnyOf("bank account", "verif");
        assertEvidenceContainsSection(r.evidence(), "bank account");
    }

    @Test
    void en_fraud_alerts() {
        ChatApiResponse r = ask("How does CDQ warn users about payment fraud?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.answer().toLowerCase()).containsAnyOf("alert", "fraud");
        assertEvidenceContainsSection(r.evidence(), "fraud alert");
    }

    // ── Group B: Cross-lingual ────────────────────────────────────────────────

    @Test
    void pl_bank_verification() {
        ChatApiResponse r = ask("Jak CDQ Fraud Guard weryfikuje rachunki bankowe?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertEvidenceContainsSection(r.evidence(), "bank account");
    }

    @Test
    void pl_trust_score() {
        ChatApiResponse r = ask("Na czym polega Trust Score?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertEvidenceContainsSection(r.evidence(), "trust score");
    }

    @Test
    void de_bank_verification() {
        ChatApiResponse r = ask("Wie überprüft CDQ Fraud Guard Bankkonten?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertEvidenceContainsSection(r.evidence(), "bank account");
    }

    @Test
    void de_trust_score() {
        ChatApiResponse r = ask("Wie funktioniert der Trust Score?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertEvidenceContainsSection(r.evidence(), "trust score");
    }

    // ── Group C: Negative — out-of-domain ────────────────────────────────────

    @Test
    void negative_weather() {
        ChatApiResponse r = ask("What is the weather in Berlin today?");
        assertThat(r.answer()).isNotBlank();
        // MCP excluded in test profile — model acknowledges it cannot retrieve weather
        // CDQ product facts must not appear in the answer
        assertThat(r.answer().toLowerCase())
            .doesNotContain("bank account verification")
            .doesNotContain("trust score")
            .doesNotContain("cdq fraud guard");
        // No CDQ chunks should be similar enough to a weather query
        assertThat(r.evidence().ragDocuments()).isEmpty();
    }

    @Test
    void negative_ceo() {
        ChatApiResponse r = ask("Who is the CEO of Microsoft?");
        assertThat(r.answer()).isNotBlank();
        // General knowledge query — model may answer from training data; no CDQ facts invented
        assertThat(r.answer().toLowerCase())
            .doesNotContain("bank account verification")
            .doesNotContain("trust score")
            .doesNotContain("cdq fraud guard");
        // Out-of-domain: no CDQ chunks should be retrieved
        assertThat(r.evidence().ragDocuments()).isEmpty();
    }

    // ── Group D: Negative — unsupported CDQ claim ─────────────────────────────

    @Test
    void negative_crypto() {
        ChatApiResponse r = ask("Does CDQ Fraud Guard support cryptocurrency payments?");
        assertThat(r.answer()).isNotBlank();
        // CDQ context is retrieved (it IS a CDQ question) but context doesn't confirm crypto support
        assertThat(r.answer().toLowerCase())
            .doesNotContain("supports cryptocurrency")
            .doesNotContain("yes, cdq fraud guard supports crypto");
    }

    // ── Group E: Evidence verification ───────────────────────────────────────

    @Test
    void evidence_source_fields() {
        ChatApiResponse r = ask("What is the Trust Score in CDQ Fraud Guard?");
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertThat(r.evidence().ragDocuments()).allSatisfy(e -> {
            assertThat(e.sourceId()).isEqualTo("cdq-fraud-guard");
            assertThat(e.sourceUrl()).contains("cdq.com");
            assertThat(e.section()).isNotBlank();
        });
    }

    @Test
    void evidence_source_version_id_positive() {
        ChatApiResponse r = ask("How does CDQ Fraud Guard verify bank accounts?");
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertThat(r.evidence().ragDocuments()).allSatisfy(e ->
            assertThat(e.sourceVersionId()).isGreaterThan(0L)
        );
    }

    @Test
    void evidence_matches_active_version() {
        Long activeVersionId = jdbcTemplate.queryForObject(
            "SELECT active_version_id FROM rag_source WHERE source_key = ?",
            Long.class, appProperties.rag().sourceId());
        assertThat(activeVersionId).isNotNull();

        ChatApiResponse r = ask("How does CDQ Fraud Guard verify bank accounts?");
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertThat(r.evidence().ragDocuments()).allSatisfy(e ->
            assertThat(e.sourceVersionId()).isEqualTo(activeVersionId)
        );
    }

    // ── Group F: EN paraphrase ────────────────────────────────────────────────

    @Test
    void en_paraphrase_trust() {
        ChatApiResponse r = ask("How does CDQ decide if a bank account can be trusted?");
        assertThat(r.answer()).isNotBlank();
        assertEvidenceContainsSection(r.evidence(), "trust score");
    }

    @Test
    void en_paraphrase_efficiency() {
        ChatApiResponse r = ask("Does the product help reduce manual verification work?");
        assertThat(r.answer()).isNotBlank();
        assertEvidenceContainsSection(r.evidence(), "operational efficiency");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChatApiResponse ask(String question) {
        return assistantService.ask(new ChatRequest(question));
    }

    private void assertEvidenceContainsSection(ExecutionEvidence ev, String sectionKeyword) {
        assertThat(ev.ragDocuments()).isNotEmpty();
        assertThat(ev.ragDocuments()).anyMatch(d ->
            d.section() != null && d.section().toLowerCase().contains(sectionKeyword.toLowerCase()));
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

    private static boolean isModelAvailable(String modelName) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                new URI("http://localhost:11434/api/tags").toURL().openConnection();
            conn.setConnectTimeout(2_000);
            conn.connect();
            String body = new String(conn.getInputStream().readAllBytes());
            return body.contains("\"" + modelName + "\"");
        } catch (Exception e) {
            return false;
        }
    }
}
