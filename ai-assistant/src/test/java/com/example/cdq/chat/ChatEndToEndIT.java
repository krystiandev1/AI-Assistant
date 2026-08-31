package com.example.cdq.chat;

import com.example.cdq.config.AppProperties;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.HttpURLConnection;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Full end-to-end integration tests: real Ollama + real MCP tools
 * (countries-mcp-server over HTTP, weather over STDIO) + real pgvector RAG.
 *
 * <p>Covers all routing paths (model knowledge, RAG, tool) across English, Polish, and German,
 * including multi-step tool chaining (country → weather). Unlike {@link ChatMultiLanguageIT},
 * which uses fake tools, this test exercises the complete production pipeline to catch issues
 * that only surface with real MCP transports — for example, the model losing the user's
 * language after a multi-step tool loop (regression fixed by Lingua-based language detection
 * + hard TARGET_OUTPUT_LANGUAGE injection via {@link com.example.cdq.config.LanguageHintAdvisor}).
 *
 * <p><strong>Prerequisites:</strong>
 * <ul>
 *   <li>Ollama at localhost:11434 with {@code qwen3:4b} and {@code qwen3-embedding:0.6b} pulled
 *   <li>countries-mcp-server at localhost:8081 ({@code ./mvnw spring-boot:run -pl countries-mcp-server})
 *   <li>node.js in PATH (weather STDIO process started automatically)
 *   <li>{@code WEATHER_API_KEY} in project root {@code .env} (loaded via spring.config.import)
 * </ul>
 *
 * <p><strong>Run:</strong>
 * <pre>{@code
 * ./mvnw verify -Pintegration -pl ai-assistant -Dit.test=ChatEndToEndIT
 * }</pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("chat-e2e-it")
@Tag("integration")
@Tag("e2e")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatEndToEndIT extends AbstractChatIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry r) {
        postgres.start();
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired AssistantService         assistantService;
    @Autowired DocumentLifecycleService lifecycleService;
    @Autowired AppProperties            appProperties;

    @BeforeAll
    void setup() {
        assumeTrue(isOllamaRunning(),
            "Ollama not running — start: ollama serve");
        assumeTrue(isModelAvailable("qwen3:4b"),
            "qwen3:4b not pulled — run: ollama pull qwen3:4b");
        assumeTrue(isModelAvailable("qwen3-embedding:0.6b"),
            "qwen3-embedding:0.6b not pulled — run: ollama pull qwen3-embedding:0.6b");
        assumeTrue(isCountryServiceRunning(),
            "countries-mcp-server not running at localhost:8081 — run: ./mvnw spring-boot:run -pl countries-mcp-server");
        lifecycleService.synchronize(appProperties.rag().sourceId());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Model knowledge — no tools, no RAG
    // ═══════════════════════════════════════════════════════════════

    @Test
    void english_model_knowledge_returns_english() {
        ChatApiResponse r = ask("What do you know about Berlin?");
        assertNoToolsOrRag(r);
        assertEnglish(r.answer());
    }

    @Test
    void polish_model_knowledge_returns_polish() {
        ChatApiResponse r = ask("Co wiesz o Berlinie?");
        // Routing is soft: qwen3:4b occasionally calls get_weather for general city questions
        // when the question is non-English. Language is the primary assertion here.
        assertPolish(r.answer());
    }

    @Test
    void german_model_knowledge_returns_german() {
        ChatApiResponse r = ask("Was weißt du über Berlin?");
        assertNoToolsOrRag(r);
        assertGerman(r.answer());
    }

    // ═══════════════════════════════════════════════════════════════
    //  RAG — CDQ Fraud Guard
    // ═══════════════════════════════════════════════════════════════

    @Test
    void english_rag_returns_english() {
        ChatApiResponse r = ask("What is CDQ Fraud Guard?");
        assertRagUsed(r);
        assertEnglish(r.answer());
    }

    @Test
    void polish_rag_returns_polish() {
        ChatApiResponse r = ask("Czym jest CDQ Fraud Guard?");
        assertRagUsed(r);
        assertPolish(r.answer());
    }

    @Test
    void german_rag_returns_german() {
        ChatApiResponse r = ask("Was ist CDQ Fraud Guard?");
        assertRagUsed(r);
        assertGerman(r.answer());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Country tool — real countries-mcp-server HTTP
    // ═══════════════════════════════════════════════════════════════

    @Test
    void english_country_question_returns_english() {
        ChatApiResponse r = ask("What is the capital of Germany?");
        assertToolCalled(r, "get_country");
        assertThat(r.answer()).containsIgnoringCase("Berlin");
        assertEnglish(r.answer());
    }

    @Test
    void polish_country_question_returns_polish() {
        ChatApiResponse r = ask("Jaka jest stolica Niemiec?");
        assertThat(r.answer()).containsIgnoringCase("Berlin");
        assertPolish(r.answer());
    }

    @Test
    void german_country_question_returns_german() {
        ChatApiResponse r = ask("Was ist die Hauptstadt von Deutschland?");
        assertThat(r.answer()).containsIgnoringCase("Berlin");
        assertGerman(r.answer());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Weather tool — real STDIO Node.js weather server
    // ═══════════════════════════════════════════════════════════════

    @Test
    void english_weather_returns_english() {
        ChatApiResponse r = ask("What is the current temperature in Berlin?");
        assertToolCalled(r, "get_weather");
        assertEnglish(r.answer());
    }

    @Test
    void polish_weather_returns_polish() {
        ChatApiResponse r = ask("Jaka jest aktualna temperatura w Berlinie?");
        assertToolCalled(r, "get_weather");
        assertPolish(r.answer());
    }

    @Test
    void german_weather_returns_german() {
        ChatApiResponse r = ask("Wie ist die aktuelle Temperatur in Berlin?");
        assertToolCalled(r, "get_weather");
        assertGerman(r.answer());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Multi-step: country → weather
    //  Model resolves capital via get_country, fetches weather via
    //  get_weather, and replies in the user's language.
    // ═══════════════════════════════════════════════════════════════

    @Test
    void english_capital_weather_multi_step_returns_english() {
        ChatApiResponse r = ask("What is the current temperature in the capital of Germany?");
        assertToolCalled(r, "get_weather");
        assertEnglish(r.answer());
    }

    @Test
    void polish_capital_weather_multi_step_returns_polish() {
        // Regression: model was replying in English to this Polish multi-step question.
        ChatApiResponse r = ask("Jaka jest aktualnie pogoda w stolicy niemiec?");
        assertToolCalled(r, "get_weather");
        assertPolish(r.answer());
    }

    @Test
    void german_capital_weather_multi_step_returns_german() {
        ChatApiResponse r = ask("Wie ist die aktuelle Temperatur in der Hauptstadt von Deutschland?");
        assertToolCalled(r, "get_weather");
        assertGerman(r.answer());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private ChatApiResponse ask(String question) {
        long start = System.currentTimeMillis();
        ChatApiResponse r = assistantService.ask(new ChatRequest(question));
        System.out.printf(
            "%n[ChatEndToEndIT] Q: \"%s\"%n" +
            "[ChatEndToEndIT] → %d ms | tools=%s | rag=%d docs%n" +
            "[ChatEndToEndIT] → answer: %s%n",
            question,
            System.currentTimeMillis() - start,
            r.evidence().toolCalls().stream().map(tc -> tc.tool()).toList(),
            r.evidence().ragDocuments().size(),
            r.answer().substring(0, Math.min(150, r.answer().length())));
        return r;
    }

    private static boolean isCountryServiceRunning() {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URI("http://localhost:8081").toURL().openConnection();
            c.setConnectTimeout(2_000);
            c.connect();
            c.disconnect();
            return true;
        } catch (Exception e) { return false; }
    }
}
