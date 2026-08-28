package com.example.cdq.chat;

import com.example.cdq.config.AppProperties;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that ActiveVersionDocumentRetriever surfaces only chunks from the current ACTIVE version.
 *
 * Uses profile chat-version-it: source-id=test-chat-version, isolated from cdq-fraud-guard data.
 * Each test resets DB state so version switching is fully isolated.
 *
 * Requirements: Docker + Ollama (qwen3:4b + qwen3-embedding:0.6b)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("chat-version-it")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatVersionIT {

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

    private static final String SOURCE_URL = "https://example.com/test-chat-version";

    // Minimal Markdown that produces 2 content chunks after header_1 filtering
    private static final String V1_CONTENT = """
        # Test Document
        Source: https://example.com/test-chat-version

        ## Features

        Our system delivers reliable data management.

        ### Feature Alpha

        Alpha provides comprehensive data stream analysis with high accuracy and low latency.
        It integrates directly with existing ERP systems via a REST API.

        ### Feature Beta

        Beta delivers real-time monitoring and instant alerting for anomalous patterns.
        Teams receive notifications within milliseconds of detection.
        """;

    private static final String V2_CONTENT = """
        # Test Document
        Source: https://example.com/test-chat-version

        ## Features

        Our updated system delivers enhanced data management.

        ### Feature Alpha

        Alpha now provides machine learning-powered analysis with greater accuracy.
        The new model is retrained weekly using community-sourced data.

        ### Feature Gamma

        Gamma replaces Beta with predictive monitoring and automated remediation.
        Incidents are resolved before users notice any degradation.
        """;

    @BeforeAll
    void checkOllamaAvailability() {
        assumeTrue(isOllamaRunning(),
            "Ollama not running — skipping ChatVersionIT. Start: ollama serve");
        assumeTrue(isModelAvailable("qwen3:4b"),
            "qwen3:4b not pulled — skipping ChatVersionIT. Run: ollama pull qwen3:4b");
        assumeTrue(isModelAvailable("qwen3-embedding:0.6b"),
            "qwen3-embedding:0.6b not pulled — skipping ChatVersionIT. Run: ollama pull qwen3-embedding:0.6b");
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("DELETE FROM vector_store");
        jdbcTemplate.execute("TRUNCATE TABLE rag_source CASCADE");
    }

    @Test
    void active_version_evidence_is_v2_after_upgrade() {
        String sourceKey = appProperties.rag().sourceId();
        lifecycleService.synchronize(sourceKey, SOURCE_URL, resource(V1_CONTENT));
        lifecycleService.synchronize(sourceKey, SOURCE_URL, resource(V2_CONTENT));

        Long v2Id = getActiveVersionId(sourceKey);
        assertThat(v2Id).isNotNull();

        ChatApiResponse r = ask("Tell me about Feature Alpha.");
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertThat(r.evidence().ragDocuments()).allSatisfy(e ->
            assertThat(e.sourceVersionId()).isEqualTo(v2Id)
        );
    }

    @Test
    void retired_chunks_absent_from_evidence() {
        String sourceKey = appProperties.rag().sourceId();
        lifecycleService.synchronize(sourceKey, SOURCE_URL, resource(V1_CONTENT));
        Long v1Id = getActiveVersionId(sourceKey);

        lifecycleService.synchronize(sourceKey, SOURCE_URL, resource(V2_CONTENT));

        ChatApiResponse r = ask("Tell me about Feature Alpha.");
        // v1 chunks must not reach the LLM context
        assertThat(r.evidence().ragDocuments()).noneMatch(e -> e.sourceVersionId() == v1Id);
    }

    @Test
    void rollback_shifts_evidence_to_v1() {
        String sourceKey = appProperties.rag().sourceId();
        lifecycleService.synchronize(sourceKey, SOURCE_URL, resource(V1_CONTENT));
        Long v1Id = getActiveVersionId(sourceKey);

        lifecycleService.synchronize(sourceKey, SOURCE_URL, resource(V2_CONTENT));
        lifecycleService.rollbackTo(v1Id);

        ChatApiResponse r = ask("Tell me about Feature Alpha.");
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertThat(r.evidence().ragDocuments()).allSatisfy(e ->
            assertThat(e.sourceVersionId()).isEqualTo(v1Id)
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChatApiResponse ask(String question) {
        return assistantService.ask(new ChatRequest(question));
    }

    private Long getActiveVersionId(String sourceKey) {
        return jdbcTemplate.queryForObject(
            "SELECT active_version_id FROM rag_source WHERE source_key = ?",
            Long.class, sourceKey);
    }

    private Resource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
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
