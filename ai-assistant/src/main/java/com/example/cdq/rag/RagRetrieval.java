package com.example.cdq.rag;

import com.example.cdq.rag.lifecycle.RagSourceRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Retrieves chunks from pgvector filtered to only the currently ACTIVE version of a source.
 * Retired chunks (from previous versions) are invisible to search even though they remain stored
 * (they are needed for rollback without re-embedding).
 */
@Component
public class RagRetrieval {

    private final VectorStore vectorStore;
    private final RagSourceRepository sourceRepository;

    public RagRetrieval(VectorStore vectorStore, RagSourceRepository sourceRepository) {
        this.vectorStore      = vectorStore;
        this.sourceRepository = sourceRepository;
    }

    /**
     * Returns topK chunks for the given query, restricted to the ACTIVE version of sourceKey.
     * Returns an empty list (never throws) if no ACTIVE version exists yet.
     */
    public List<Document> search(String sourceKey, String query, int topK, double threshold) {
        Long activeVersionId = sourceRepository.findActiveVersionId(sourceKey);
        if (activeVersionId == null) {
            return List.of();
        }

        var filter = new FilterExpressionBuilder()
            .eq("sourceVersionId", activeVersionId)
            .build();

        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .filterExpression(filter)
                .build()
        );
    }
}
