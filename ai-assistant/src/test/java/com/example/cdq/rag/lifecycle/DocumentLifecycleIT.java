package com.example.cdq.rag.lifecycle;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for DocumentLifecycleService.
 *
 * Requirements: Docker + Ollama + qwen3-embedding:0.6b
 * Profile:      lifecycle-it (startup-sync-enabled=false, sql.init.mode=always)
 *
 * Each test gets a clean DB state via @BeforeEach truncation.
 * Ollama must be available; otherwise the whole class is skipped.
 *
 * Krok 8 will add: RagRetrieval-based active-version filtering and end-to-end rollback retrieval tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("lifecycle-it")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentLifecycleIT {

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

    @Autowired DocumentLifecycleService lifecycleService;
    @Autowired RagVersionTransactions    tx;
    @Autowired RagSourceRepository       sourceRepo;
    @Autowired RagSourceVersionRepository versionRepo;
    @Autowired JdbcTemplate              jdbcTemplate;

    private static final String SOURCE_KEY = "test-source";
    private static final String SOURCE_URL = "https://example.com/test";

    // Minimal markdown with real h1/h2/h3 structure (matches MarkdownDocumentReader expectations)
    private static final String V1_CONTENT = """
        # Test Document
        Source: https://example.com/test

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
        Source: https://example.com/test

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
            "Ollama not available — skipping DocumentLifecycleIT. " +
            "Start with: ollama serve && ollama pull qwen3-embedding:0.6b");
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("DELETE FROM vector_store");
        // CASCADE clears rag_source_version (FK: source_id → rag_source.id) in one statement
        jdbcTemplate.execute("TRUNCATE TABLE rag_source CASCADE");
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    void first_sync_creates_active_version() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));

        RagSource source = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow();
        assertThat(source.activeVersionId()).isNotNull();

        RagSourceVersion version = versionRepo.findById(source.activeVersionId()).orElseThrow();
        assertThat(version.status()).isEqualTo(VersionStatus.ACTIVE);
        assertThat(version.chunkCount()).isGreaterThan(0);
        assertThat(version.activatedAt()).isNotNull();
    }

    @Test
    void second_sync_same_content_creates_no_new_version() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        long firstVersionId = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();

        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));

        long versionCount = countVersions(SOURCE_KEY);
        assertThat(versionCount).isEqualTo(1);

        long activeVersionId = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();
        assertThat(activeVersionId).isEqualTo(firstVersionId);
    }

    @Test
    void second_sync_same_content_adds_no_vectors() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        int countAfterFirst = countVectors();

        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        int countAfterSecond = countVectors();

        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
    }

    // ── Change detection ──────────────────────────────────────────────────────

    @Test
    void sync_with_changed_content_creates_new_version() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V2_CONTENT));

        long versionCount = countVersions(SOURCE_KEY);
        assertThat(versionCount).isEqualTo(2);
    }

    @Test
    void new_version_has_different_source_hash() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        long v1Id = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();

        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V2_CONTENT));
        long v2Id = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();

        String hash1 = versionRepo.findById(v1Id).orElseThrow().sourceHash();
        String hash2 = versionRepo.findById(v2Id).orElseThrow().sourceHash();
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void old_version_is_retired_after_content_change() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        long v1Id = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();

        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V2_CONTENT));

        RagSourceVersion v1 = versionRepo.findById(v1Id).orElseThrow();
        assertThat(v1.status()).isEqualTo(VersionStatus.RETIRED);
    }

    @Test
    void new_version_is_active_after_content_change() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V2_CONTENT));

        long activeId = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();
        RagSourceVersion active = versionRepo.findById(activeId).orElseThrow();
        assertThat(active.status()).isEqualTo(VersionStatus.ACTIVE);
    }

    @Test
    void active_version_pointer_updated_after_content_change() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        long v1Id = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();

        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V2_CONTENT));
        long v2Id = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();

        assertThat(v2Id).isNotEqualTo(v1Id);
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Test
    void all_chunks_have_source_version_id() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));

        int nullCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'sourceVersionId' IS NULL",
            Integer.class);
        assertThat(nullCount).isZero();
    }

    @Test
    void all_chunks_source_version_id_matches_active_version() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));

        long activeVersionId = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();
        int matchingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'sourceVersionId' = ?",
            Integer.class, String.valueOf(activeVersionId));
        int totalCount = countVectors();
        assertThat(matchingCount).isEqualTo(totalCount).isGreaterThan(0);
    }

    @Test
    void all_chunks_have_source_hash() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));

        int nullCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'sourceHash' IS NULL",
            Integer.class);
        assertThat(nullCount).isZero();

        // All sourceHash values are 64-char hex strings
        List<String> hashes = jdbcTemplate.queryForList(
            "SELECT DISTINCT metadata->>'sourceHash' FROM vector_store", String.class);
        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).hasSize(64);
    }

    @Test
    void all_chunks_have_pipeline_fingerprint() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));

        int nullCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'pipelineFingerprint' IS NULL",
            Integer.class);
        assertThat(nullCount).isZero();
    }

    @Test
    void all_chunks_have_chunk_hash() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));

        int nullCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'chunkHash' IS NULL",
            Integer.class);
        assertThat(nullCount).isZero();

        // Each chunk has a unique hash
        int totalCount = countVectors();
        int distinctHashes = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT metadata->>'chunkHash') FROM vector_store", Integer.class);
        assertThat(distinctHashes).isEqualTo(totalCount);
    }

    // ── Rollback guards ───────────────────────────────────────────────────────

    @Test
    void rollback_to_already_active_version_is_noop() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        long activeId = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();

        // Should not throw, should not change state
        lifecycleService.rollbackTo(activeId);

        long stillActiveId = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();
        assertThat(stillActiveId).isEqualTo(activeId);
        assertThat(versionRepo.findById(activeId).orElseThrow().status()).isEqualTo(VersionStatus.ACTIVE);
    }

    @Test
    void rollback_to_retired_version_makes_it_active() {
        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V1_CONTENT));
        long v1Id = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();

        lifecycleService.synchronize(SOURCE_KEY, SOURCE_URL, resource(V2_CONTENT));
        long v2Id = sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId();

        lifecycleService.rollbackTo(v1Id);

        assertThat(versionRepo.findById(v1Id).orElseThrow().status()).isEqualTo(VersionStatus.ACTIVE);
        assertThat(versionRepo.findById(v2Id).orElseThrow().status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(sourceRepo.findBySourceKey(SOURCE_KEY).orElseThrow().activeVersionId()).isEqualTo(v1Id);
    }

    @Test
    void rollback_to_failed_version_throws() {
        // Simulate a PROCESSING version that failed before activation
        RagSource source = sourceRepo.upsert(SOURCE_KEY, SOURCE_URL);
        String hash = DocumentHasher.sha256Hex("some-content");
        String fingerprint = DocumentHasher.sha256Hex(hash + "|model|1024|v1");
        long failedVersionId = versionRepo.insert(
            source.id(), hash, fingerprint,
            new PipelineConfig("model", 1024, "v1"));
        versionRepo.markFailed(failedVersionId, "test failure");

        assertThatThrownBy(() -> lifecycleService.rollbackTo(failedVersionId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FAILED");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Resource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private int countVectors() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Integer.class);
    }

    private long countVersions(String sourceKey) {
        return jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM rag_source_version rsv
            JOIN rag_source rs ON rs.id = rsv.source_id
            WHERE rs.source_key = ?
            """,
            Long.class, sourceKey);
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
