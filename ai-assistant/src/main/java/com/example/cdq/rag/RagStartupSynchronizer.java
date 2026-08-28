package com.example.cdq.rag;

import com.example.cdq.config.AppProperties;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Synchronizes the configured RAG source document at startup.
 * Idempotent: if the ACTIVE version already matches the current file + pipeline fingerprint, it skips.
 *
 * Disabled in integration tests via: app.rag.startup-sync-enabled: false
 * (tests call lifecycleService.synchronize() explicitly)
 */
@Component
@ConditionalOnProperty(name = "app.rag.startup-sync-enabled", havingValue = "true", matchIfMissing = true)
public class RagStartupSynchronizer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagStartupSynchronizer.class);

    private final AppProperties appProperties;
    private final DocumentLifecycleService lifecycleService;

    public RagStartupSynchronizer(AppProperties appProperties,
                                  DocumentLifecycleService lifecycleService) {
        this.appProperties   = appProperties;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String sourceKey = appProperties.rag().sourceId();
        log.info("[Startup] Synchronizing RAG source '{}'", sourceKey);
        try {
            lifecycleService.synchronize(sourceKey);
            log.info("[Startup] RAG source '{}' synchronized successfully", sourceKey);
        } catch (Exception e) {
            log.error("[Startup] RAG synchronization failed for '{}': {}", sourceKey, e.getMessage(), e);
            // Do not rethrow — a startup failure should not crash the application.
            // Retrieval will return empty results until the source is successfully ingested.
        }
    }
}
