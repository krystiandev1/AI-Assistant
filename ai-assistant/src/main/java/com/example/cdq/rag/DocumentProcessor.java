package com.example.cdq.rag;

import com.example.cdq.config.AppProperties;
import com.example.cdq.rag.lifecycle.DocumentHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentProcessor {

    public static final String PROCESSOR_VERSION = "v1";

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

    private final AppProperties appProperties;

    public DocumentProcessor(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Processes a canonical resource (already normalized and hashed by the caller) into enriched chunks.
     * The caller is responsible for providing consistent canonicalResource, sourceHash, and pipelineFingerprint.
     */
    public List<Document> process(Resource canonicalResource, long versionId,
                                   String sourceHash, String pipelineFingerprint) {
        List<Document> rawDocs = readMarkdown(canonicalResource);
        logRawOutput(rawDocs);
        Map<String, String> parentSectionMap = buildParentSectionMap(canonicalResource);
        return enrich(rawDocs, parentSectionMap, versionId, sourceHash, pipelineFingerprint);
    }

    private List<Document> readMarkdown(Resource resource) {
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
            .withHorizontalRuleCreateDocument(false)
            .withIncludeCodeBlock(false)
            .withIncludeBlockquote(false)
            .build();
        return new MarkdownDocumentReader(resource, config).read();
    }

    private void logRawOutput(List<Document> docs) {
        log.info("[DocumentProcessor] MarkdownDocumentReader produced {} document(s)", docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String preview = doc.getText().length() > 80
                ? doc.getText().substring(0, 80).replace('\n', ' ') + "..."
                : doc.getText().replace('\n', ' ');
            log.info("  [{}] metadata={} | content_len={} | preview: {}",
                i, doc.getMetadata(), doc.getText().length(), preview);
        }
    }

    // Parses heading hierarchy from the canonical resource: maps each ### heading → its parent ## heading.
    private Map<String, String> buildParentSectionMap(Resource resource) {
        Map<String, String> parentMap = new LinkedHashMap<>();
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            String currentH2 = null;
            for (String line : content.split("\\R")) {
                if (line.startsWith("## ")) {
                    currentH2 = line.substring(3).trim();
                } else if (line.startsWith("### ") && currentH2 != null) {
                    String h3 = line.substring(4).trim();
                    parentMap.put(h3, currentH2);
                }
            }
        } catch (IOException e) {
            log.warn("[DocumentProcessor] Could not parse heading hierarchy: {}", e.getMessage());
        }
        return parentMap;
    }

    private List<Document> enrich(List<Document> rawDocs, Map<String, String> parentSectionMap,
                                   long versionId, String sourceHash, String pipelineFingerprint) {
        String sourceId  = appProperties.rag().sourceId();
        String sourceUrl = appProperties.rag().sourceUrl();
        List<Document> result = new ArrayList<>();
        int chunkIndex = 0;

        for (Document doc : rawDocs) {
            if (doc.getText().isBlank()) continue;
            // Skip document-level header (header_1): contains only source metadata, not retrievable knowledge.
            // Its embedding (just the source URL) matches all CDQ-branded queries and pollutes ranking.
            if ("header_1".equals(doc.getMetadata().get("category"))) continue;

            String section = extractSection(doc);
            String parentSection = parentSectionMap.get(section);

            Map<String, Object> meta = new HashMap<>(doc.getMetadata());
            meta.put("sourceId",            sourceId);
            meta.put("sourceUrl",           sourceUrl);
            meta.put("section",             section);
            meta.put("chunkIndex",          chunkIndex++);
            meta.put("sourceVersionId",     versionId);
            meta.put("sourceHash",          sourceHash);
            meta.put("pipelineFingerprint", pipelineFingerprint);
            meta.put("chunkHash",           DocumentHasher.sha256Hex(doc.getText()));
            if (parentSection != null) {
                meta.put("parentSection", parentSection);
            }

            result.add(new Document(doc.getText(), meta));
        }

        log.info("[DocumentProcessor] Enriched {} chunk(s) (filtered {} blank)", result.size(),
            rawDocs.size() - result.size());
        return result;
    }

    // MarkdownDocumentReader (Spring AI 2.0.1) stores: category = "header_1|2|3", title = heading text
    private String extractSection(Document doc) {
        Object title = doc.getMetadata().get("title");
        if (title != null && !title.toString().isBlank()) {
            return title.toString().trim();
        }
        return doc.getText().lines()
            .filter(l -> !l.isBlank())
            .findFirst()
            .orElse("unknown")
            .trim();
    }
}
