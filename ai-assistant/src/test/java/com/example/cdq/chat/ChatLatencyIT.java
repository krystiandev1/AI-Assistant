package com.example.cdq.chat;

import com.example.cdq.config.AppProperties;
import com.example.cdq.rag.RagRetrieval;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Diagnostic latency breakdown for the chat pipeline.
 *
 * NOT a regression test — all scenarios always pass, results are printed to stdout.
 *
 * Measures:
 *   1. Direct LLM (no Spring AI), think=ON  — cold + warm
 *   2. Direct LLM (no Spring AI), think=OFF — cold + warm
 *   3. Retrieval only (embedding → pgvector) — cold + warm
 *   4. Full RAG via AssistantService        — cold + warm
 *
 * Per-LLM-call Ollama metrics: load_duration, prompt_eval_duration, eval_duration,
 *   prompt_eval_count (input tokens), eval_count (output tokens)
 *
 * Run: mvn verify -Pintegration -pl ai-assistant -Dit.test=ChatLatencyIT -Drun.latency=true
 *
 * Requires: Docker + Ollama with qwen3:4b + qwen3-embedding:0.6b
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("chat-it")
@Tag("integration")   // required by -Pintegration Failsafe groups filter
@Tag("latency")       // semantic tag for explicit filtering
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatLatencyIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

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
    @Autowired RagRetrieval             ragRetrieval;

    private static final String OLLAMA_BASE = "http://localhost:11434";
    private static final String CHAT_MODEL  = "qwen3:4b";
    private static final String EMBED_MODEL = "qwen3-embedding:0.6b";
    private static final String SIMPLE_Q    = "What is 2+2?";
    private static final String CDQ_Q       = "What is the Trust Score in CDQ Fraud Guard?";

    private final List<Row> rows = new ArrayList<>();
    private final HttpClient     http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // ── Result row ────────────────────────────────────────────────────────────

    record Row(
        String  scenario,
        long    totalMs,
        Long    loadMs,         // Ollama load_duration (ms)
        Long    promptEvalMs,   // Ollama prompt_eval_duration (ms)
        Long    evalMs,         // Ollama eval_duration (ms) — includes think tokens
        Integer inTokens,       // prompt_eval_count
        Integer outTokens,      // eval_count — includes think tokens
        long    retrievalMs,    // wall-clock retrieval time (0 if N/A)
        String  answer
    ) {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeAll
    void setup() throws Exception {
        // Guard: skip unless explicitly enabled — env var is inherited by forked JVM
        // Windows: $env:RUN_LATENCY=true; ./mvnw verify -Pintegration -pl ai-assistant -Dit.test=ChatLatencyIT
        // Linux:   RUN_LATENCY=true ./mvnw verify -Pintegration -pl ai-assistant -Dit.test=ChatLatencyIT
        assumeTrue(System.getenv("RUN_LATENCY") != null,
            "Latency diagnostic skipped — set env var RUN_LATENCY=true to enable");
        assumeTrue(isOllamaRunning(),             "Ollama not running");
        assumeTrue(isModelAvailable(CHAT_MODEL),  CHAT_MODEL  + " not pulled");
        assumeTrue(isModelAvailable(EMBED_MODEL), EMBED_MODEL + " not pulled");

        printOllamaPs("=== OLLAMA PS (before tests) ===");
        lifecycleService.synchronize(appProperties.rag().sourceId());
    }

    // ── 1. Direct LLM — think=ON ──────────────────────────────────────────────

    @Test @Order(1)
    void llm_simple_think_on() throws Exception {
        rows.add(ollamaChat("direct simple think=ON",  SIMPLE_Q, true));
    }

    @Test @Order(2)
    void llm_cdq_think_on_cold() throws Exception {
        unloadModel(CHAT_MODEL);
        rows.add(ollamaChat("CDQ think=ON  cold", CDQ_Q, true));
    }

    @Test @Order(3)
    void llm_cdq_think_on_warm() throws Exception {
        rows.add(ollamaChat("CDQ think=ON  warm", CDQ_Q, true));
    }

    // ── 2. Direct LLM — think=OFF ─────────────────────────────────────────────

    @Test @Order(4)
    void llm_simple_think_off() throws Exception {
        rows.add(ollamaChat("direct simple think=OFF", SIMPLE_Q, false));
    }

    @Test @Order(5)
    void llm_cdq_think_off_cold() throws Exception {
        unloadModel(CHAT_MODEL);
        rows.add(ollamaChat("CDQ think=OFF cold", CDQ_Q, false));
    }

    @Test @Order(6)
    void llm_cdq_think_off_warm() throws Exception {
        rows.add(ollamaChat("CDQ think=OFF warm", CDQ_Q, false));
    }

    // ── 3. Retrieval only (embed → pgvector) ──────────────────────────────────

    @Test @Order(7)
    void retrieval_cold() {
        long t = now();
        int n = ragRetrieval.search(appProperties.rag().sourceId(), CDQ_Q, 4, 0.0).size();
        long ms = elapsed(t);
        rows.add(new Row("retrieval only (cold)", ms, null, null, null, null, null, ms, n + " chunks"));
    }

    @Test @Order(8)
    void retrieval_warm() {
        long t = now();
        int n = ragRetrieval.search(appProperties.rag().sourceId(), CDQ_Q, 4, 0.0).size();
        long ms = elapsed(t);
        rows.add(new Row("retrieval only (warm)", ms, null, null, null, null, null, ms, n + " chunks"));
    }

    // ── 4. Full RAG (AssistantService path) ───────────────────────────────────

    @Test @Order(9)
    void full_rag_cold() throws Exception {
        // Force model unload so first call includes load time
        unloadModel(CHAT_MODEL);

        long total = now();
        ChatApiResponse r = assistantService.ask(new ChatRequest(CDQ_Q));
        long totalMs = elapsed(total);

        // Separate retrieval measurement (approximation — second call, same query)
        long ret = now();
        ragRetrieval.search(appProperties.rag().sourceId(), CDQ_Q, 4, 0.0);
        long retMs = elapsed(ret);

        rows.add(new Row("full RAG (cold)", totalMs, null, null, null, null, null, retMs, truncate(r.answer())));
    }

    @Test @Order(10)
    void full_rag_warm() {
        long total = now();
        ChatApiResponse r = assistantService.ask(new ChatRequest(CDQ_Q));
        long totalMs = elapsed(total);

        long ret = now();
        ragRetrieval.search(appProperties.rag().sourceId(), CDQ_Q, 4, 0.0);
        long retMs = elapsed(ret);

        rows.add(new Row("full RAG (warm)", totalMs, null, null, null, null, null, retMs, truncate(r.answer())));
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    @AfterAll
    void printSummary() throws Exception {
        printOllamaPs("\n=== OLLAMA PS (after tests) ===");

        String bar = "=".repeat(115);
        String header = String.format("\n%s%n  CHAT LATENCY DIAGNOSTIC — model: %s%n%s%n%-32s %10s %10s %14s %11s %7s %7s %9s   %s%n%s%n",
            bar, CHAT_MODEL, bar,
            "Scenario", "Total(ms)", "Load(ms)", "PromptEv(ms)", "Eval(ms)",
            "InTok", "OutTok", "Retr(ms)", "Answer preview",
            "-".repeat(115));

        StringBuilder sb = new StringBuilder(header);
        for (Row r : rows) {
            sb.append(String.format("%-32s %10d %10s %14s %11s %7s %7s %9d   %s%n",
                r.scenario(),
                r.totalMs(),
                r.loadMs()       != null ? r.loadMs()       : "-",
                r.promptEvalMs() != null ? r.promptEvalMs() : "-",
                r.evalMs()       != null ? r.evalMs()       : "-",
                r.inTokens()     != null ? r.inTokens()     : "-",
                r.outTokens()    != null ? r.outTokens()    : "-",
                r.retrievalMs(),
                r.answer()));
        }
        sb.append(bar).append("\n\n  KEY FINDINGS:\n");

        long thinkOnWarm  = find("think=ON  warm").map(Row::totalMs).orElse(-1L);
        long thinkOffWarm = find("think=OFF warm").map(Row::totalMs).orElse(-1L);
        long retWarm      = find("retrieval only (warm)").map(Row::totalMs).orElse(-1L);
        long ragCold      = find("full RAG (cold)").map(Row::totalMs).orElse(-1L);
        long ragWarm      = find("full RAG (warm)").map(Row::totalMs).orElse(-1L);
        long ragRetWarm   = find("full RAG (warm)").map(Row::retrievalMs).orElse(-1L);

        if (thinkOnWarm > 0)  sb.append(String.format("    think=ON  (warm):    %,6d ms%n", thinkOnWarm));
        if (thinkOffWarm > 0) sb.append(String.format("    think=OFF (warm):    %,6d ms", thinkOffWarm));
        if (thinkOnWarm > 0 && thinkOffWarm > 0)
            sb.append(String.format("   → think overhead: %,d ms (%.1fx)%n",
                thinkOnWarm - thinkOffWarm, (double) thinkOnWarm / thinkOffWarm));
        else sb.append("\n");
        if (retWarm > 0)      sb.append(String.format("    retrieval (warm):    %,6d ms%n", retWarm));
        if (ragCold > 0)      sb.append(String.format("    full RAG cold:       %,6d ms%n", ragCold));
        if (ragWarm > 0) {
            sb.append(String.format("    full RAG warm:       %,6d ms", ragWarm));
            if (ragRetWarm > 0)
                sb.append(String.format("   (retrieval ~%d ms | LLM ~%d ms)%n",
                    ragRetWarm, ragWarm - ragRetWarm));
            else sb.append("\n");
        }
        sb.append(bar).append("\n");

        String report = sb.toString();
        System.out.println(report);

        // Write to file so it's readable after Failsafe captures stdout
        try {
            java.nio.file.Path out = java.nio.file.Paths.get("target/latency-report.txt");
            java.nio.file.Files.writeString(out, report);
            System.out.println("Report written to: " + out.toAbsolutePath());
        } catch (Exception e) {
            System.out.println("Could not write report file: " + e.getMessage());
        }
    }

    // ── Ollama HTTP ───────────────────────────────────────────────────────────

    private Row ollamaChat(String scenario, String prompt, boolean think) throws Exception {
        // "think" is a top-level Ollama parameter for qwen3 models (Ollama ≥ 0.6)
        String body = """
            {
              "model": "%s",
              "think": %b,
              "stream": false,
              "options": {"temperature": 0.0},
              "messages": [{"role": "user", "content": "%s"}]
            }
            """.formatted(CHAT_MODEL, think, escapeJson(prompt));

        long start = now();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(OLLAMA_BASE + "/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long totalMs = elapsed(start);

        String raw = resp.body();
        // Extract "content" from "message":{"role":"assistant","content":"..."}
        String content = extractContent(raw);

        return new Row(
            scenario, totalMs,
            ns2ms(raw, "load_duration"),
            ns2ms(raw, "prompt_eval_duration"),
            ns2ms(raw, "eval_duration"),
            extractInt(raw, "prompt_eval_count"),
            extractInt(raw, "eval_count"),
            0,
            truncate(content));
    }

    private void unloadModel(String model) throws Exception {
        String body = """
            {"model": "%s", "keep_alive": 0}
            """.formatted(model);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(OLLAMA_BASE + "/api/generate"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        http.send(req, HttpResponse.BodyHandlers.discarding());
        Thread.sleep(2_000); // give Ollama time to release the model
    }

    private void printOllamaPs(String header) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(OLLAMA_BASE + "/api/ps"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String raw = resp.body();

        System.out.println("\n" + header);
        // Print raw ps response for full detail (GPU, processor, etc.)
        if (raw.contains("\"name\"")) {
            // Extract each model entry
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"name\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(raw);
            while (m.find()) System.out.println("  loaded: " + m.group(1));
        } else {
            System.out.println("  (no models currently loaded)");
        }
        // Print size_vram if present
        if (raw.contains("size_vram")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"size_vram\"\\s*:\\s*(\\d+)")
                .matcher(raw);
            if (m.find()) System.out.println("  VRAM used: " + humanBytes(Long.parseLong(m.group(1))));
        }
        System.out.println("  raw: " + raw.substring(0, Math.min(300, raw.length())));
        System.out.println();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private Optional<Row> find(String substr) {
        return rows.stream().filter(r -> r.scenario().contains(substr)).findFirst();
    }

    private static long now()                 { return System.nanoTime(); }
    private static long elapsed(long nanoStart) { return (System.nanoTime() - nanoStart) / 1_000_000; }

    // JSON helpers — lightweight regex parsing, avoids Jackson dependency
    private static Long ns2ms(String rawJson, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + field + "\"\\s*:\\s*(\\d+)")
            .matcher(rawJson);
        return m.find() ? Long.parseLong(m.group(1)) / 1_000_000 : null;
    }

    private static Integer extractInt(String rawJson, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + field + "\"\\s*:\\s*(\\d+)")
            .matcher(rawJson);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** Extract the assistant message content from the Ollama /api/chat response. */
    private static String extractContent(String rawJson) {
        // Manual parse — avoids regex StackOverflowError on long think responses
        // Scan from the END to find the last "content": "..."
        int lastIdx = rawJson.lastIndexOf("\"content\"");
        if (lastIdx < 0) return rawJson.substring(0, Math.min(80, rawJson.length()));

        int colon = rawJson.indexOf(':', lastIdx);
        if (colon < 0) return rawJson.substring(0, Math.min(80, rawJson.length()));

        int i = colon + 1;
        while (i < rawJson.length() && Character.isWhitespace(rawJson.charAt(i))) i++;
        if (i >= rawJson.length() || rawJson.charAt(i) != '"') {
            return rawJson.substring(0, Math.min(80, rawJson.length()));
        }
        i++; // skip opening quote

        StringBuilder sb = new StringBuilder();
        while (i < rawJson.length()) {
            char c = rawJson.charAt(i);
            if (c == '"') break; // closing quote
            if (c == '\\' && i + 1 < rawJson.length()) {
                char next = rawJson.charAt(i + 1);
                sb.append(switch (next) {
                    case 'n' -> ' ';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> next;
                });
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null || s.isBlank()) return "-";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 65 ? s.substring(0, 62) + "..." : s;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String humanBytes(long bytes) {
        if (bytes <= 0) return "-";
        String[] u = {"B", "KB", "MB", "GB"};
        double v = bytes; int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format("%.1f%s", v, u[i]);
    }

    private static boolean isOllamaRunning() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new URI(OLLAMA_BASE).toURL().openConnection();
            c.setConnectTimeout(2_000); c.connect();
            return c.getResponseCode() >= 0;
        } catch (Exception e) { return false; }
    }

    private static boolean isModelAvailable(String name) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new URI(OLLAMA_BASE + "/api/tags").toURL().openConnection();
            c.setConnectTimeout(2_000); c.connect();
            return new String(c.getInputStream().readAllBytes()).contains("\"" + name + "\"");
        } catch (Exception e) { return false; }
    }
}
