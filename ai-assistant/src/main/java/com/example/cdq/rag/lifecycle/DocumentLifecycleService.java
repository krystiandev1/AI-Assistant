package com.example.cdq.rag.lifecycle;

import com.example.cdq.config.AppProperties;
import com.example.cdq.rag.DocumentProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Orchestrates the full RAG document lifecycle: hash → skip/create → embed → validate → activate.
 *
 * Not @Transactional — long Ollama I/O must run outside a transaction.
 * Transactional DB operations are delegated to RagVersionTransactions (@Component).
 */
@Service
public class DocumentLifecycleService implements ResourceLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(DocumentLifecycleService.class);

    private final AppProperties appProperties;
    private final DocumentProcessor documentProcessor;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final PipelineFingerprinter fingerprinter;
    private final RagVersionTransactions tx;
    private final RagSourceVersionRepository versionRepo;

    @Value("${spring.ai.ollama.embedding.model}")
    private String embeddingModel;

    private ResourceLoader resourceLoader;

    public DocumentLifecycleService(AppProperties appProperties,
                                    DocumentProcessor documentProcessor,
                                    VectorStore vectorStore,
                                    JdbcTemplate jdbcTemplate,
                                    PipelineFingerprinter fingerprinter,
                                    RagVersionTransactions tx,
                                    RagSourceVersionRepository versionRepo) {
        this.appProperties  = appProperties;
        this.documentProcessor = documentProcessor;
        this.vectorStore    = vectorStore;
        this.jdbcTemplate   = jdbcTemplate;
        this.fingerprinter  = fingerprinter;
        this.tx             = tx;
        this.versionRepo    = versionRepo;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /** Convenience: reads sourceUrl and resource path from AppProperties. */
    public void synchronize(String sourceKey) {
        String sourceUrl = appProperties.rag().sourceUrl();
        Resource raw = resourceLoader.getResource(appProperties.rag().resourcePath());
        synchronize(sourceKey, sourceUrl, raw);
    }

    /**
     * Core synchronization logic. Tests can pass a ByteArrayResource with any content
     * to test change-detection and idempotency without touching the classpath file.
     */
    public void synchronize(String sourceKey, String sourceUrl, Resource rawResource) {
        // 1. Read and normalize canonical content ONCE — same bytes go to hash and processor
        String canonicalContent;
        try {
            canonicalContent = DocumentHasher.normalize(
                rawResource.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read RAG source: " + sourceKey, e);
        }

        // 2. Compute hashes
        String sourceHash     = DocumentHasher.sha256Hex(canonicalContent);
        PipelineConfig config = buildPipelineConfig();
        String fingerprint    = fingerprinter.compute(sourceHash, config);

        // 3. Skip if an ACTIVE version with this fingerprint already exists
        if (versionRepo.existsActiveWithFingerprint(sourceKey, fingerprint)) {
            log.info("[Lifecycle] Source '{}' unchanged (fingerprint match) — skipping ingestion", sourceKey);
            return;
        }

        // 4. Persist PROCESSING version (short TX)
        long versionId = tx.createProcessingVersion(sourceKey, sourceUrl, sourceHash, fingerprint, config);

        try {
            // 5. Process chunks (outside TX — reads markdown, builds metadata)
            Resource canonical = new ByteArrayResource(canonicalContent.getBytes(StandardCharsets.UTF_8));
            List<Document> chunks = documentProcessor.process(canonical, versionId, sourceHash, fingerprint);

            // 6. Store embeddings (Ollama I/O — may take seconds; outside TX)
            vectorStore.add(chunks);

            // 7. Validate chunk count in pgvector
            int storedCount = countStoredChunks(versionId);
            if (storedCount != chunks.size()) {
                throw new IngestionValidationException(
                    "Chunk count mismatch: expected=%d stored=%d versionId=%d"
                        .formatted(chunks.size(), storedCount, versionId));
            }

            // 8. Atomically retire old ACTIVE, activate new version (short TX)
            long sourceId = versionRepo.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("Version row missing: " + versionId))
                .sourceId();
            tx.activateVersion(sourceId, versionId, chunks.size());

        } catch (Exception e) {
            // REQUIRES_NEW: persists FAILED regardless of any outer transaction rollback
            tx.markFailed(versionId, e.getMessage());
            // Rethrow so callers can react (startup runner logs and exits; tests see the failure)
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Ingestion failed for source: " + sourceKey, e);
        }
    }

    /** Rolls back to a previous ACTIVE or RETIRED version (no re-embedding). */
    public void rollbackTo(long targetVersionId) {
        tx.rollbackTo(targetVersionId);
    }

    private int countStoredChunks(long versionId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'sourceVersionId' = ?",
            Integer.class, String.valueOf(versionId));
        return count != null ? count : 0;
    }

    private PipelineConfig buildPipelineConfig() {
        return new PipelineConfig(
            embeddingModel,
            appProperties.rag().embeddingExpectedDimensions(),
            DocumentProcessor.PROCESSOR_VERSION
        );
    }
}
