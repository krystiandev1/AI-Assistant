package com.example.cdq.chat;

import com.example.cdq.config.AppProperties;
import com.example.cdq.evidence.EvidenceCapturingToolCallbackProvider;
import com.example.cdq.evidence.McpToolResultDecoder;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.HttpURLConnection;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Multi-language response verification.
 *
 * Proves that AssistantService.withLanguageHint() correctly detects Polish and German
 * and prepends the appropriate language directive to the user message, causing qwen3:4b
 * to mirror the input language in its response across all three routing paths:
 * model knowledge, RAG, and MCP tool calls.
 *
 * Language detection strategy:
 *   Polish  — Polish diacritics (ą ć ę ł ń ó ś ź ż) OR common Polish words
 *   German  — German umlauts (ä ö ü Ä Ö Ü ß) OR unambiguous German words
 *   English — no hint prepended; model defaults to English
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("chat-routing-it")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatMultiLanguageIT {

    private static final String POLISH_CHARS = "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ";
    private static final String GERMAN_CHARS  = "äöüÄÖÜß";

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry r) {
        postgres.start();
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",           postgres::getUsername);
        r.add("spring.datasource.password",           postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired AssistantService         assistantService;
    @Autowired DocumentLifecycleService lifecycleService;
    @Autowired AppProperties            appProperties;

    @BeforeAll
    void setup() {
        assumeTrue(isOllamaRunning(), "Ollama not running");
        assumeTrue(isModelAvailable("qwen3:4b"), "qwen3:4b not pulled");
        assumeTrue(isModelAvailable("qwen3-embedding:0.6b"), "qwen3-embedding:0.6b not pulled");
        lifecycleService.synchronize(appProperties.rag().sourceId());
    }

    // ═══════════════════════════════════════════════════════════════
    //  MODEL KNOWLEDGE PATH
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

        assertNoToolsOrRag(r);
        assertPolish(r.answer());
    }

    @Test
    void german_model_knowledge_returns_german() {
        ChatApiResponse r = ask("Was weißt du über Berlin?");

        assertNoToolsOrRag(r);
        assertGerman(r.answer());
    }

    // ═══════════════════════════════════════════════════════════════
    //  RAG PATH  (CDQ Fraud Guard questions)
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
    //  MCP TOOL PATH  (get_country)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void english_country_question_returns_english() {
        ChatApiResponse r = ask("What is the capital of Germany?");

        assertToolCalled(r, "get_country");
        assertEnglish(r.answer());
    }

    @Test
    void polish_country_question_returns_polish() {
        ChatApiResponse r = ask("Jaka jest stolica Niemiec?");

        assertToolCalled(r, "get_country");
        assertPolish(r.answer());
    }

    @Test
    void german_country_question_returns_german() {
        ChatApiResponse r = ask("Was ist die Hauptstadt von Deutschland?");

        assertToolCalled(r, "get_country");
        assertGerman(r.answer());
    }

    // ═══════════════════════════════════════════════════════════════
    //  MCP TOOL PATH  (get-weather) — also checks English tool args
    // ═══════════════════════════════════════════════════════════════

    @Test
    void english_weather_question_uses_english_city_arg() {
        ChatApiResponse r = ask("What is the current temperature in Munich?");

        assertToolCalled(r, "get-weather");
        assertToolArgContains(r, "get-weather", "Munich");
        assertEnglish(r.answer());
    }

    @Test
    void polish_weather_question_uses_english_city_arg() {
        ChatApiResponse r = ask("Jaka jest aktualna temperatura w Monachium?");

        assertToolCalled(r, "get-weather");
        // Model must translate "Monachium" → "Munich" before calling the tool
        assertToolArgContains(r, "get-weather", "Munich");
        assertPolish(r.answer());
    }

    @Test
    void german_weather_question_uses_english_city_arg() {
        ChatApiResponse r = ask("Wie ist die aktuelle Temperatur in München?");

        assertToolCalled(r, "get-weather");
        // Model must translate "München" → "Munich" before calling the tool
        assertToolArgContains(r, "get-weather", "Munich");
        assertGerman(r.answer());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Language assertion helpers
    // ═══════════════════════════════════════════════════════════════

    private static void assertPolish(String text) {
        boolean hasPolishDiacritics = text.chars().anyMatch(c -> POLISH_CHARS.indexOf(c) >= 0);
        boolean hasPolishWords = text.toLowerCase().matches(
            ".*\\b(jest|nie|tak|przez|oraz|który|która|które|tego|tej|ten|ta|to|się|jak|co)\\b.*");
        assertThat(hasPolishDiacritics || hasPolishWords)
            .as("Expected Polish response.\nActual: %s", text)
            .isTrue();
    }

    private static void assertGerman(String text) {
        boolean hasGermanChars = text.chars().anyMatch(c -> GERMAN_CHARS.indexOf(c) >= 0);
        boolean hasGermanWords = text.toLowerCase().matches(
            ".*\\b(ist|sind|haben|nicht|die|der|das|ein|eine|und|von|für|mit|auf|als)\\b.*");
        assertThat(hasGermanChars || hasGermanWords)
            .as("Expected German response.\nActual: %s", text)
            .isTrue();
    }

    private static void assertEnglish(String text) {
        boolean noPolishChars = text.chars().noneMatch(c -> POLISH_CHARS.indexOf(c) >= 0);
        boolean noGermanChars = text.chars().noneMatch(c -> GERMAN_CHARS.indexOf(c) >= 0);
        boolean hasEnglishWords = text.toLowerCase().matches(
            ".*\\b(is|are|the|of|in|and|to|a|an|it|this|that|for|with|has|have|be)\\b.*");
        assertThat(noPolishChars && noGermanChars && hasEnglishWords)
            .as("Expected English response.\nActual: %s", text)
            .isTrue();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Routing assertion helpers
    // ═══════════════════════════════════════════════════════════════

    private static void assertNoToolsOrRag(ChatApiResponse r) {
        assertThat(r.evidence().toolCalls()).as("No tool calls expected").isEmpty();
        assertThat(r.evidence().ragDocuments()).as("No RAG docs expected").isEmpty();
    }

    private static void assertRagUsed(ChatApiResponse r) {
        assertThat(r.evidence().toolCalls()).as("No tool calls expected for RAG path").isEmpty();
        assertThat(r.evidence().ragDocuments()).as("RAG docs must be retrieved").isNotEmpty();
    }

    private static void assertToolCalled(ChatApiResponse r, String toolName) {
        assertThat(r.evidence().toolCalls())
            .as("Expected tool '%s' to be called", toolName)
            .anyMatch(tc -> toolName.equals(tc.tool()));
    }

    private static void assertToolArgContains(ChatApiResponse r, String toolName, String expected) {
        assertThat(r.evidence().toolCalls())
            .filteredOn(tc -> toolName.equals(tc.tool()))
            .as("Tool '%s' arg must contain '%s'", toolName, expected)
            .anyMatch(tc -> tc.argumentsJson() != null
                && tc.argumentsJson().toLowerCase().contains(expected.toLowerCase()));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Timing helper
    // ═══════════════════════════════════════════════════════════════

    private ChatApiResponse ask(String question) {
        long start = System.currentTimeMillis();
        ChatApiResponse r = assistantService.ask(new ChatRequest(question));
        System.out.printf("%n[ChatMultiLanguageIT] Q: \"%s\"%n" +
                          "[ChatMultiLanguageIT] → time: %d ms | first 120 chars: %s%n",
            question, System.currentTimeMillis() - start,
            r.answer().substring(0, Math.min(120, r.answer().length())));
        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fake MCP tools  (same pattern as ChatToolRoutingIT)
    // ═══════════════════════════════════════════════════════════════

    @TestConfiguration
    static class FakeToolsConfig {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Bean
        ToolCallbacksFactory toolCallbacksFactory(McpToolResultDecoder decoder) {
            ToolCallbackProvider fakeProvider = ToolCallbackProvider.from(
                fakeGetCountry(), fakeGetWeather());
            return evidence -> new Object[]{
                new EvidenceCapturingToolCallbackProvider(fakeProvider, "fake-mcp", evidence, decoder)
            };
        }

        private static ToolCallback fakeGetCountry() {
            ToolDefinition def = ToolDefinition.builder()
                .name("get_country")
                .description("Retrieves factual information about a country: name, capital city, " +
                             "region, and population. Use this tool for any question about " +
                             "country facts, capitals, or country-specific data.")
                .inputSchema("""
                    {"type":"object","properties":{
                      "countryName":{"type":"string","description":"Common English country name, e.g. 'Germany'"}
                    },"required":["countryName"]}""")
                .build();
            return new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return def; }
                @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
                @Override public String call(String input) {
                    String country = field(input, "countryName");
                    String data = switch (country.toLowerCase()) {
                        case "france"  -> """
                            {"commonName":"France","capital":"Paris","region":"Europe","population":67400000}""";
                        case "poland"  -> """
                            {"commonName":"Poland","capital":"Warsaw","region":"Europe","population":38000000}""";
                        default        -> """
                            {"commonName":"Germany","capital":"Berlin","region":"Europe","population":83200000}""";
                    };
                    return mcpOk("{\"status\":\"OK\",\"data\":" + data + "}");
                }
            };
        }

        private static ToolCallback fakeGetWeather() {
            ToolDefinition def = ToolDefinition.builder()
                .name("get-weather")
                .description("Get the current weather for a city. " +
                             "Use this tool whenever current temperature or weather conditions are requested.")
                .inputSchema("""
                    {"type":"object","properties":{
                      "city":{"type":"string","description":"City name in English, e.g. 'Munich', 'Berlin'"}
                    },"required":["city"]}""")
                .build();
            return new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return def; }
                @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
                @Override public String call(String input) {
                    String city = field(input, "city");
                    double temp = switch (city.toLowerCase()) {
                        case "berlin"  -> 15.0;
                        case "munich"  -> 18.5;
                        case "paris"   -> 20.0;
                        case "warsaw"  -> 12.0;
                        default        -> 17.0;
                    };
                    return mcpOk(String.format(
                        "{\"status\":\"OK\",\"city\":\"%s\",\"temperatureCelsius\":%.1f}", city, temp));
                }
            };
        }

        private static String field(String json, String key) {
            try {
                JsonNode n = MAPPER.readTree(json);
                String v = n.path(key).asString(null);
                return v != null ? v : "";
            } catch (Exception e) { return ""; }
        }

        private static String mcpOk(String payload) {
            String escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"");
            return "[{\"type\":\"text\",\"text\":\"" + escaped + "\"}]";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Infrastructure checks
    // ═══════════════════════════════════════════════════════════════

    private static boolean isOllamaRunning() {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URI("http://localhost:11434").toURL().openConnection();
            c.setConnectTimeout(2_000);
            c.connect();
            return c.getResponseCode() >= 0;
        } catch (Exception e) { return false; }
    }

    private static boolean isModelAvailable(String modelName) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URI("http://localhost:11434/api/tags").toURL().openConnection();
            c.setConnectTimeout(2_000);
            c.connect();
            String body = new String(c.getInputStream().readAllBytes());
            return body.contains("\"" + modelName + "\"");
        } catch (Exception e) { return false; }
    }
}
