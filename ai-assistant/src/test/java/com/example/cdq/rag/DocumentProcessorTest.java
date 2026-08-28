package com.example.cdq.rag;

import com.example.cdq.config.AppProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DocumentProcessor. No Spring context, no Ollama, no database.
 *
 * Step 0 (Krok 0): test_markdown_reader_raw_output reveals what MarkdownDocumentReader
 * actually produces for our document — use its output to verify DocumentProcessor logic.
 *
 * Run: mvn test -pl ai-assistant
 */
class DocumentProcessorTest {

    private static final String RESOURCE_PATH = "rag/cdq-fraud-guard.md";
    private static DocumentProcessor processor;
    private static List<Document> documents;

    @BeforeAll
    static void setUp() {
        AppProperties props = new AppProperties(
            new AppProperties.Countries("http://localhost", "key"),
            new AppProperties.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(10)),
            new AppProperties.Rag(0.5, 1024, "cdq-fraud-guard",
                "https://www.cdq.com/products/cdq-fraud-guard",
                "classpath:" + RESOURCE_PATH),
            new AppProperties.Weather("key", "./mcp-weather")
        );
        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        processor = new DocumentProcessor(props);
        processor.setResourceLoader(resourceLoader);
        documents = processor.process();
    }

    // ── Krok 0: diagnostics ──────────────────────────────────────────────────

    /**
     * Logs what MarkdownDocumentReader actually produces.
     * Run this first and read the output to verify DocumentProcessor assumptions.
     */
    @Test
    void krok0_markdown_reader_raw_output() {
        Resource resource = new ClassPathResource(RESOURCE_PATH);
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
            .withHorizontalRuleCreateDocument(false)
            .withIncludeCodeBlock(false)
            .withIncludeBlockquote(false)
            .build();
        List<Document> rawDocs = new MarkdownDocumentReader(resource, config).read();

        System.out.println("=== MarkdownDocumentReader RAW OUTPUT ===");
        System.out.println("Total documents: " + rawDocs.size());
        for (int i = 0; i < rawDocs.size(); i++) {
            Document doc = rawDocs.get(i);
            String preview = doc.getText().length() > 100
                ? doc.getText().substring(0, 100).replace('\n', ' ') + "..."
                : doc.getText().replace('\n', ' ');
            System.out.printf("  [%d] metadata=%s | len=%d | %s%n",
                i, doc.getMetadata(), doc.getText().length(), preview);
        }
        System.out.println("=== END RAW OUTPUT ===");

        // Always passes — diagnostic only
        assertThat(rawDocs).isNotEmpty();
    }

    // ── Document resource ────────────────────────────────────────────────────

    @Test
    void document_resource_exists_and_is_not_empty() {
        Resource resource = new ClassPathResource(RESOURCE_PATH);
        assertThat(resource.exists()).isTrue();
        assertThat(resource.isReadable()).isTrue();
    }

    @Test
    void document_contains_bank_account_verification_section() {
        assertThat(anyChunkMentions("Bank Account Verification")).isTrue();
    }

    @Test
    void document_contains_trust_score_section() {
        assertThat(anyChunkMentions("Trust Score")).isTrue();
    }

    @Test
    void document_contains_payment_fraud_alerts_section() {
        assertThat(anyChunkMentions("Payment Fraud Alerts")).isTrue();
    }

    @Test
    void document_contains_operational_efficiency_section() {
        assertThat(anyChunkMentions("Operational Efficiency")).isTrue();
    }

    // ── Processing results ───────────────────────────────────────────────────

    @Test
    void processed_documents_are_not_empty_list() {
        assertThat(documents).isNotEmpty();
    }

    @Test
    void no_chunk_has_blank_content() {
        assertThat(documents).allSatisfy(doc ->
            assertThat(doc.getText()).isNotBlank()
        );
    }

    @Test
    void chunk_count_is_within_reasonable_range() {
        assertThat(documents.size())
            .as("Expected between 8 and 25 chunks, got %d", documents.size())
            .isBetween(8, 25);
    }

    @Test
    void all_chunks_have_sourceId_metadata() {
        assertThat(documents).allSatisfy(doc ->
            assertThat(doc.getMetadata()).containsKey("sourceId")
        );
        assertThat(documents).allSatisfy(doc ->
            assertThat(doc.getMetadata().get("sourceId")).isEqualTo("cdq-fraud-guard")
        );
    }

    @Test
    void all_chunks_have_sourceUrl_metadata() {
        assertThat(documents).allSatisfy(doc ->
            assertThat(doc.getMetadata()).containsKey("sourceUrl")
        );
    }

    @Test
    void all_chunks_have_section_metadata() {
        assertThat(documents).allSatisfy(doc -> {
            assertThat(doc.getMetadata()).containsKey("section");
            assertThat(doc.getMetadata().get("section")).isNotNull();
            assertThat(doc.getMetadata().get("section").toString()).isNotBlank();
        });
    }

    @Test
    void all_chunks_have_chunkIndex_metadata() {
        assertThat(documents).allSatisfy(doc ->
            assertThat(doc.getMetadata()).containsKey("chunkIndex")
        );
    }

    @Test
    void chunkIndex_values_are_sequential() {
        for (int i = 0; i < documents.size(); i++) {
            int idx = (Integer) documents.get(i).getMetadata().get("chunkIndex");
            assertThat(idx).isEqualTo(i);
        }
    }

    @Test
    void processing_is_deterministic() {
        List<Document> second = processor.process();

        assertThat(second).hasSameSizeAs(documents);
        for (int i = 0; i < documents.size(); i++) {
            assertThat(second.get(i).getText()).isEqualTo(documents.get(i).getText());
            assertThat(second.get(i).getMetadata().get("section"))
                .isEqualTo(documents.get(i).getMetadata().get("section"));
        }
    }

    @Test
    void trust_score_chunk_content_mentions_trust_score() {
        boolean found = documents.stream()
            .filter(d -> sectionContains(d, "Trust Score"))
            .anyMatch(d -> d.getText().toLowerCase().contains("trust score"));
        assertThat(found).as("Trust Score chunk should contain 'trust score' in content").isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean anyChunkMentions(String phrase) {
        String lower = phrase.toLowerCase();
        return documents.stream().anyMatch(d -> {
            String section = d.getMetadata().getOrDefault("section", "").toString().toLowerCase();
            return section.contains(lower) || d.getText().toLowerCase().contains(lower);
        });
    }

    private boolean sectionContains(Document doc, String phrase) {
        Object section = doc.getMetadata().get("section");
        return section != null && section.toString().toLowerCase().contains(phrase.toLowerCase());
    }
}
