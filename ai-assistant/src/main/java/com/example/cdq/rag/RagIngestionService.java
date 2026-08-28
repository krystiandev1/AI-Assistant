package com.example.cdq.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    private final DocumentProcessor documentProcessor;
    private final VectorStore vectorStore;

    public RagIngestionService(DocumentProcessor documentProcessor, VectorStore vectorStore) {
        this.documentProcessor = documentProcessor;
        this.vectorStore = vectorStore;
    }

    public void ingest() {
        List<Document> documents = documentProcessor.process();
        log.info("[RagIngestionService] Storing {} document chunk(s) to vector store", documents.size());
        vectorStore.add(documents);
        log.info("[RagIngestionService] Ingestion complete");
    }
}
