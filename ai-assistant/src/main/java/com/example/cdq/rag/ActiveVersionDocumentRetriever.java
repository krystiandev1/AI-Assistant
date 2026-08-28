package com.example.cdq.rag;

import com.example.cdq.config.AppProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ActiveVersionDocumentRetriever implements DocumentRetriever {

    private final RagRetrieval ragRetrieval;
    private final AppProperties appProperties;

    public ActiveVersionDocumentRetriever(RagRetrieval ragRetrieval, AppProperties appProperties) {
        this.ragRetrieval   = ragRetrieval;
        this.appProperties  = appProperties;
    }

    @Override
    public List<Document> retrieve(Query query) {
        return ragRetrieval.search(
            appProperties.rag().sourceId(),
            query.text(),
            4,
            appProperties.rag().similarityThreshold()
        );
    }
}
