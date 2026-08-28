package com.example.cdq.rag;

import com.example.cdq.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentProcessor implements ResourceLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

    private final AppProperties appProperties;
    private ResourceLoader resourceLoader;

    public DocumentProcessor(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public List<Document> process() {
        Resource resource = resourceLoader.getResource(appProperties.rag().resourcePath());
        List<Document> rawDocs = readMarkdown(resource);
        logRawOutput(rawDocs);
        Map<String, String> parentSectionMap = buildParentSectionMap(resource);
        return enrich(rawDocs, parentSectionMap);
    }

    private List<Document> readMarkdown(Resource resource) {
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
            .withHorizontalRuleCreateDocument(false)
            .withIncludeCodeBlock(false)
            .withIncludeBlockquote(false)
            .build();
        return new MarkdownDocumentReader(resource, config).read();
    }

    // Krok 0: log actual MarkdownDocumentReader output to understand its behavior
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

    // Parses heading hierarchy from raw Markdown: maps each ### heading → its parent ## heading.
    // Independent of MarkdownDocumentReader behavior.
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

    private List<Document> enrich(List<Document> rawDocs, Map<String, String> parentSectionMap) {
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
            meta.put("sourceId",    sourceId);
            meta.put("sourceUrl",   sourceUrl);
            meta.put("section",     section);
            meta.put("chunkIndex",  chunkIndex++);
            if (parentSection != null) {
                meta.put("parentSection", parentSection);
            }

            result.add(new Document(doc.getText(), meta));
        }

        log.info("[DocumentProcessor] Enriched {} chunk(s) (filtered {} blank)", result.size(),
            rawDocs.size() - result.size());
        return result;
    }

    // Extracts the section name from MarkdownDocumentReader metadata.
    // MarkdownDocumentReader (Spring AI 2.0.1) stores:
    //   category = "header_1" | "header_2" | "header_3"  (heading level, not title)
    //   title    = actual heading text                     (what we want as section name)
    private String extractSection(Document doc) {
        Object title = doc.getMetadata().get("title");
        if (title != null && !title.toString().isBlank()) {
            return title.toString().trim();
        }
        // Fallback: derive from content (helps diagnose unexpected reader output in future versions)
        return doc.getText().lines()
            .filter(l -> !l.isBlank())
            .findFirst()
            .orElse("unknown")
            .trim();
    }
}
