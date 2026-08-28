package com.example.cdq.rag;

import com.example.cdq.config.AppProperties;
import com.example.cdq.rag.lifecycle.DocumentHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @deprecated Replaced by DocumentLifecycleService (Stage 3). Kept as a shim until RagPipelineIT is
 * fully migrated to the lifecycle API in Krok 8.
 */
@Deprecated
@Service
public class RagIngestionService implements ResourceLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    private final AppProperties appProperties;
    private final DocumentProcessor documentProcessor;
    private final VectorStore vectorStore;
    private ResourceLoader resourceLoader;

    public RagIngestionService(AppProperties appProperties,
                               DocumentProcessor documentProcessor,
                               VectorStore vectorStore) {
        this.appProperties = appProperties;
        this.documentProcessor = documentProcessor;
        this.vectorStore = vectorStore;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public void ingest() {
        Resource raw = resourceLoader.getResource(appProperties.rag().resourcePath());
        try {
            String canonicalContent = DocumentHasher.normalize(
                raw.getContentAsString(StandardCharsets.UTF_8));
            String sourceHash   = DocumentHasher.sha256Hex(canonicalContent);
            String fingerprint  = DocumentHasher.sha256Hex(sourceHash + "|legacy|0|" + DocumentProcessor.PROCESSOR_VERSION);
            Resource canonical  = new ByteArrayResource(canonicalContent.getBytes(StandardCharsets.UTF_8));

            List<Document> documents = documentProcessor.process(canonical, 0L, sourceHash, fingerprint);
            log.info("[RagIngestionService] Storing {} chunk(s) to vector store", documents.size());
            vectorStore.add(documents);
            log.info("[RagIngestionService] Ingestion complete");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read RAG source: " + appProperties.rag().resourcePath(), e);
        }
    }
}
