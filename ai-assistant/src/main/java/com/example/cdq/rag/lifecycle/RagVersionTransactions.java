package com.example.cdq.rag.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional operations for the RAG document lifecycle.
 *
 * Extracted into a separate Spring bean so that each method is called through the AOP proxy —
 * self-invocation from DocumentLifecycleService would bypass @Transactional entirely.
 */
@Component
public class RagVersionTransactions {

    private static final Logger log = LoggerFactory.getLogger(RagVersionTransactions.class);

    private final RagSourceRepository sourceRepo;
    private final RagSourceVersionRepository versionRepo;

    public RagVersionTransactions(RagSourceRepository sourceRepo,
                                  RagSourceVersionRepository versionRepo) {
        this.sourceRepo = sourceRepo;
        this.versionRepo = versionRepo;
    }

    /**
     * Upserts rag_source and inserts a new PROCESSING version row.
     * Short transaction — no I/O to Ollama or pgvector.
     */
    @Transactional
    public long createProcessingVersion(String sourceKey, String sourceUrl,
                                        String sourceHash, String pipelineFingerprint,
                                        PipelineConfig config) {
        RagSource source = sourceRepo.upsert(sourceKey, sourceUrl);
        long versionId = versionRepo.insert(source.id(), sourceHash, pipelineFingerprint, config);
        log.info("[Lifecycle] Created PROCESSING version id={} for source='{}'", versionId, sourceKey);
        return versionId;
    }

    /**
     * Atomically retires the current ACTIVE version and activates the new one.
     * Short transaction — three UPDATEs under a single commit.
     * The unique partial index on (source_id) WHERE status='ACTIVE' prevents concurrent activation.
     */
    @Transactional
    public void activateVersion(long sourceId, long versionId, int chunkCount) {
        versionRepo.retireActive(sourceId);
        versionRepo.activate(versionId, chunkCount);
        sourceRepo.setActiveVersion(sourceId, versionId);
        log.info("[Lifecycle] Activated version id={} (chunkCount={})", versionId, chunkCount);
    }

    /**
     * Marks a version as FAILED with an error message.
     * Uses REQUIRES_NEW so this commit succeeds even if the caller's transaction is rolling back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long versionId, String reason) {
        versionRepo.markFailed(versionId, reason);
        log.warn("[Lifecycle] Marked version id={} as FAILED: {}", versionId, reason);
    }

    /**
     * Rolls back to a previously ACTIVE or RETIRED version.
     * No-op if the target version is already ACTIVE.
     * No vector store operations — existing chunks are reused.
     */
    @Transactional
    public void rollbackTo(long targetVersionId) {
        RagSourceVersion target = versionRepo.findById(targetVersionId)
            .orElseThrow(() -> new IllegalArgumentException("Version not found: " + targetVersionId));

        if (target.status() == VersionStatus.ACTIVE) {
            log.info("[Lifecycle] Version {} is already ACTIVE — rollback is a no-op", targetVersionId);
            return;
        }
        if (target.status() == VersionStatus.PROCESSING || target.status() == VersionStatus.FAILED) {
            throw new IllegalStateException(
                "Cannot activate version " + targetVersionId + " with status: " + target.status());
        }

        versionRepo.retireActive(target.sourceId());
        versionRepo.activate(targetVersionId);
        sourceRepo.setActiveVersion(target.sourceId(), targetVersionId);
        log.info("[Lifecycle] Rolled back to version id={}", targetVersionId);
    }
}
