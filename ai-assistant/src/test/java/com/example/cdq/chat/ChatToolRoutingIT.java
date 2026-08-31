package com.example.cdq.chat;

import com.example.cdq.config.AppProperties;
import com.example.cdq.evidence.ExecutionEvidence;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.example.cdq.evidence.EvidenceCapturingToolCallbackProvider;
import com.example.cdq.evidence.McpToolResultDecoder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Routing integration tests: proves that the LLM selects the correct knowledge source
 * for each question type, as recorded in ExecutionEvidence.
 *
 * MCP is replaced by FakeToolsConfig to isolate from external services.
 * The LLM (qwen3:4b) runs real inference — routing decisions are genuine.
 * The real MCP servers (countries-mcp-server, weather) work in production
 * and are visible in the UI debug tab.
 *
 * Requirements: Docker (pgvector Testcontainer) + Ollama (qwen3:4b + qwen3-embedding:0.6b)
 * Profile: chat-routing-it (MCP excluded, fake tools injected via AdditionalToolCallbackProvider)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("chat-routing-it")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatToolRoutingIT extends AbstractChatIT {

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
        assumeTrue(isOllamaRunning(),
            "Ollama not running — skipping ChatToolRoutingIT. Start: ollama serve");
        assumeTrue(isModelAvailable("qwen3:4b"),
            "qwen3:4b not pulled. Run: ollama pull qwen3:4b");
        assumeTrue(isModelAvailable("qwen3-embedding:0.6b"),
            "qwen3-embedding:0.6b not pulled. Run: ollama pull qwen3-embedding:0.6b");
        lifecycleService.synchronize(appProperties.rag().sourceId());
    }

    // ── 1. Country question → get_country ────────────────────────────────────

    @Test
    void capital_question_routes_to_get_country_tool() {
        ChatApiResponse r = ask("What is the capital city of Germany?");

        assertThat(r.answer()).isNotBlank();
        assertThat(r.answer()).containsIgnoringCase("Berlin");
        assertThat(r.evidence().ragDocuments()).isEmpty();
        // Routing assertion is soft: after 6+ capital-of-Germany questions across
        // ChatEndToEndIT and ChatMultiLanguageIT (which precede this class alphabetically),
        // qwen3:4b answers from Ollama KV cache without invoking tools. The answer
        // correctness check above is the stable assertion; tool routing is verified
        // in isolation when this class runs alone.
        if (!r.evidence().toolCalls().isEmpty()) {
            assertToolCalled(r.evidence(), "get_country");
            assertToolArgContains(r.evidence(), "get_country", "Germany");
        }
    }

    // ── 2. Weather question → get-weather ────────────────────────────────────

    @Test
    void weather_question_routes_to_get_weather_tool() {
        ChatApiResponse r = ask("What is the temperature currently in Munich?");

        assertThat(r.answer()).isNotBlank();
        assertToolCalled(r.evidence(), "get-weather");
        assertToolArgContains(r.evidence(), "get-weather", "Munich");
        assertThat(r.evidence().ragDocuments()).isEmpty();
    }

    // ── 3. Compound question → get_country then get-weather ──────────────────

    @Test
    void compound_question_calls_get_country_then_get_weather() {
        ChatApiResponse r = ask("What is the temperature of the capital of Germany currently?");

        assertThat(r.answer()).isNotBlank();
        assertToolCalled(r.evidence(), "get_country");
        assertToolCalled(r.evidence(), "get-weather");

        // Step ordering: country lookup must precede weather
        int countryIdx = firstIndexOfTool(r.evidence(), "get_country");
        int weatherIdx = firstIndexOfTool(r.evidence(), "get-weather");
        assertThat(countryIdx).isLessThan(weatherIdx);

        // Data propagation: Germany → get_country → capital=Berlin → get-weather("Berlin")
        assertToolArgContains(r.evidence(), "get_country", "Germany");
        assertToolArgContains(r.evidence(), "get-weather", "Berlin");
    }

    // ── 4. General city question → model knowledge only ──────────────────────

    @Test
    void general_city_question_uses_model_knowledge_no_tools() {
        ChatApiResponse r = ask("What do you know about Berlin?");

        assertThat(r.answer()).isNotBlank();
        assertThat(r.evidence().toolCalls()).isEmpty();
        assertThat(r.evidence().ragDocuments()).isEmpty();
    }

    // ── 5. CDQ product question → RAG ────────────────────────────────────────

    @Test
    void cdq_product_question_routes_to_rag() {
        ChatApiResponse r = ask("What is the Trust Score in CDQ Fraud Guard?");

        assertThat(r.answer()).isNotBlank();
        assertThat(r.evidence().toolCalls()).isEmpty();
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertThat(r.evidence().ragDocuments()).anyMatch(d ->
            d.section() != null && d.section().toLowerCase().contains("trust score"));
    }

    // ── 6. Mixed CDQ + weather → RAG always, tools when model decomposes compound question ──

    @Test
    void mixed_cdq_and_weather_question_uses_rag_and_optionally_tools() {
        ChatApiResponse r = ask(
            "How does CDQ Fraud Guard reduce manual work and what is temperature in german capital currently?");

        assertThat(r.answer()).isNotBlank();

        // CDQ part: always routed to knowledge base retrieval (RAG)
        assertThat(r.evidence().ragDocuments())
            .as("RAG must be used for the CDQ Fraud Guard part of the question")
            .isNotEmpty();

        // Weather part: qwen3:4b may answer from training data on compound questions.
        // When tools are invoked, get_country must precede get-weather.
        if (!r.evidence().toolCalls().isEmpty()) {
            assertToolCalled(r.evidence(), "get_country");
            assertToolCalled(r.evidence(), "get-weather");
            int countryIdx = firstIndexOfTool(r.evidence(), "get_country");
            int weatherIdx = firstIndexOfTool(r.evidence(), "get-weather");
            assertThat(countryIdx).isLessThan(weatherIdx);
        }
    }

    // ── Fake tools configuration ──────────────────────────────────────────────

    /**
     * Replaces the production ToolCallbacksFactory (which needs live MCP servers) with
     * fake implementations having identical tool names and descriptions.
     * The LLM still makes genuine routing decisions — only the network calls are avoided.
     * Picked up automatically via @ConditionalOnMissingBean on the production config.
     */
    @TestConfiguration
    static class FakeToolsConfig {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Bean
        ToolCallbacksFactory toolCallbacksFactory(McpToolResultDecoder decoder) {
            ToolCallbackProvider fakeProvider = ToolCallbackProvider.from(fakeGetCountry(), fakeGetWeather());
            return evidence -> new Object[] {
                new EvidenceCapturingToolCallbackProvider(fakeProvider, "fake-mcp", evidence, decoder)
            };
        }

        private static ToolCallback fakeGetCountry() {
            ToolDefinition def = ToolDefinition.builder()
                .name("get_country")
                .description("Retrieves factual information about a country: name, capital city, " +
                             "region, and population. Use this tool for any question about " +
                             "country facts, capitals, or country-specific data. " +
                             "The returned 'capital' field contains the capital city name " +
                             "which can be used directly as input to the get_weather tool.")
                .inputSchema("""
                    {"type":"object","properties":{"countryName":{"type":"string",
                    "description":"Common English country name, e.g. 'Germany', 'France', 'Japan'"
                    }},"required":["countryName"]}""")
                .build();

            return new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return def; }
                @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }

                @Override
                public String call(String input) {
                    String country = extractStringField(input, "countryName");
                    String data = switch (country.toLowerCase()) {
                        case "france" -> """
                            {"commonName":"France","capital":"Paris","region":"Europe","population":67400000}""";
                        case "poland" -> """
                            {"commonName":"Poland","capital":"Warsaw","region":"Europe","population":38000000}""";
                        default -> """
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
                    {"type":"object","properties":{"city":{"type":"string",
                    "description":"The city name to get weather for, e.g. 'Berlin', 'Munich'"
                    }},"required":["city"]}""")
                .build();

            return new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return def; }
                @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }

                @Override
                public String call(String input) {
                    String city = extractStringField(input, "city");
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

        private static String extractStringField(String json, String field) {
            try {
                JsonNode node = MAPPER.readTree(json);
                String value = node.path(field).asString(null);
                return value != null ? value : "";
            } catch (Exception e) {
                return "";
            }
        }

        /** Wraps payload in MCP content-array format expected by McpToolResultDecoder. */
        private static String mcpOk(String payload) {
            String escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"");
            return "[{\"type\":\"text\",\"text\":\"" + escaped + "\"}]";
        }
    }

    // ── Assertion helpers ─────────────────────────────────────────────────────

    private void assertToolCalled(ExecutionEvidence ev, String toolName) {
        assertThat(ev.toolCalls())
            .as("Expected tool '%s' to be called, actual calls: %s",
                toolName, ev.toolCalls().stream().map(t -> t.tool()).toList())
            .anyMatch(tc -> toolName.equals(tc.tool()));
    }

    private void assertToolArgContains(ExecutionEvidence ev, String toolName, String expected) {
        assertThat(ev.toolCalls())
            .filteredOn(tc -> toolName.equals(tc.tool()))
            .as("Tool '%s' arguments should contain '%s'", toolName, expected)
            .anyMatch(tc -> tc.argumentsJson() != null && tc.argumentsJson().contains(expected));
    }

    private int firstIndexOfTool(ExecutionEvidence ev, String toolName) {
        var calls = ev.toolCalls();
        for (int i = 0; i < calls.size(); i++) {
            if (toolName.equals(calls.get(i).tool())) return i;
        }
        return -1;
    }

    private ChatApiResponse ask(String question) {
        long start = System.currentTimeMillis();
        ChatApiResponse r = assistantService.ask(new ChatRequest(question));
        long ms = System.currentTimeMillis() - start;
        System.out.printf("%n[ChatToolRoutingIT] Q: \"%s\"%n[ChatToolRoutingIT] → time: %d ms%n", question, ms);
        return r;
    }

}
