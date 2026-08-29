package com.example.cdq.chat;

import com.example.cdq.config.AppProperties;
import com.example.cdq.evidence.ExecutionEvidence;
import com.example.cdq.rag.RagRetrieval;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Full production pipeline benchmark and quality tests.
 *
 * Covers Tests 1-4 from the performance investigation sprint:
 *   Test 1+2: AssistantService.ask() wall-clock + ChatModel call count/timing
 *   Test 3:   RAG quality assertions (EN/PL/DE, evidence sections, sourceVersionId)
 *   Test 4:   Tool calling smoke test
 *
 * Guard: set RUN_FULL_BENCH=true to enable. Skipped otherwise.
 * Model: controlled by OLLAMA_CHAT_MODEL env var (default: qwen3:4b).
 *   Run twice to compare:
 *     RUN_FULL_BENCH=true ./mvnw verify -Pintegration -pl ai-assistant -Dit.test=FullPipelineBenchmarkIT
 *     OLLAMA_CHAT_MODEL=qwen3:4b-instruct RUN_FULL_BENCH=true ./mvnw verify ...
 *
 * Report written to: target/full-pipeline-report.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("chat-it")
@Tag("integration")
@Tag("benchmark")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(FullPipelineBenchmarkIT.SpyConfig.class)
class FullPipelineBenchmarkIT {

    // ── Container ─────────────────────────────────────────────────────────────

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        postgres.start();
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        String model = System.getenv().getOrDefault("OLLAMA_CHAT_MODEL", "qwen3:4b");
        r.add("spring.ai.ollama.chat.model", () -> model);
        System.out.println("[FullPipelineBenchmarkIT] spring.ai.ollama.chat.model=" + model);
    }

    // ── ChatModel spy ─────────────────────────────────────────────────────────

    static class ChatModelSpy implements ChatModel {

        private final ChatModel delegate;
        final List<CallRecord> calls = new CopyOnWriteArrayList<>();

        record CallRecord(int n, long ms, Integer inTok, Integer outTok) {}

        ChatModelSpy(ChatModel delegate) { this.delegate = delegate; }

        void reset() { calls.clear(); }

        int totalInTok()  { return calls.stream().mapToInt(c -> c.inTok()  != null ? c.inTok()  : 0).sum(); }
        int totalOutTok() { return calls.stream().mapToInt(c -> c.outTok() != null ? c.outTok() : 0).sum(); }
        long sumCallMs()  { return calls.stream().mapToLong(c -> c.ms()).sum(); }

        @Override
        public ChatResponse call(Prompt prompt) {
            int n = calls.size() + 1;
            long t = System.nanoTime();
            ChatResponse resp = delegate.call(prompt);
            long ms = (System.nanoTime() - t) / 1_000_000;

            Integer in = null, out = null;
            if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                var u = resp.getMetadata().getUsage();
                in  = u.getPromptTokens();
                out = u.getCompletionTokens();
            }
            calls.add(new CallRecord(n, ms, in, out));
            System.out.printf("  [ChatModel call #%d] %,d ms  inTok=%s  outTok=%s%n", n, ms, in, out);
            return resp;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return delegate.stream(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }
    }

    // ── Spy test configuration ────────────────────────────────────────────────

    @TestConfiguration
    static class SpyConfig {
        @Bean
        @Primary
        ChatModelSpy chatModelSpy(OllamaChatModel delegate) {
            return new ChatModelSpy(delegate);
        }
    }

    // ── Tool for smoke test ───────────────────────────────────────────────────

    static class TemperatureTool {
        final List<String> invocations = new CopyOnWriteArrayList<>();

        @Tool(description = "Get the current temperature in Celsius for a given city name")
        public String getCurrentTemperature(String city) {
            System.out.printf("  [TOOL INVOKED] getCurrentTemperature('%s')%n", city);
            invocations.add(city);
            return "22 degrees Celsius";
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    @Autowired ChatModelSpy             chatModelSpy;
    @Autowired AssistantService         assistantService;
    @Autowired ChatClient               chatClient;
    @Autowired DocumentLifecycleService lifecycleService;
    @Autowired AppProperties            appProperties;
    @Autowired RagRetrieval             ragRetrieval;
    @Autowired JdbcTemplate             jdbcTemplate;

    private final String model = System.getenv().getOrDefault("OLLAMA_CHAT_MODEL", "qwen3:4b");

    private static final String CDQ_Q = "What is the Trust Score in CDQ Fraud Guard?";

    record BenchRun(String label, long wallMs, int llmCalls, long sumCallMs,
                    Integer inTok, Integer outTok, long retMs, String answer, List<String> sections) {}

    final List<BenchRun>  benchResults   = new ArrayList<>();
    final List<String>    qualityResults = new ArrayList<>();
    final TemperatureTool tempTool       = new TemperatureTool();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeAll
    void setup() {
        assumeTrue(System.getenv("RUN_FULL_BENCH") != null,
            "Skipped — set RUN_FULL_BENCH=true to enable full pipeline benchmark");
        assumeTrue(isOllamaRunning(), "Ollama not running — start: ollama serve");
        assumeTrue(isModelAvailable(model),
            model + " not pulled — run: ollama pull " + model);
        assumeTrue(isModelAvailable("qwen3-embedding:0.6b"),
            "qwen3-embedding:0.6b not pulled");

        System.out.printf("%n=== FullPipelineBenchmarkIT  model=%s ===%n", model);
        lifecycleService.synchronize(appProperties.rag().sourceId());
        System.out.println("  ingestion complete\n");
    }

    // ── Test 1+2: Full pipeline benchmark + LLM call counting ─────────────────

    @Test @Order(10)
    void t1_cold() {
        unloadModel();
        benchResults.add(fullRagCall("COLD"));
    }

    @Test @Order(20)
    void t1_warm1() { benchResults.add(fullRagCall("WARM-1")); }

    @Test @Order(30)
    void t1_warm2() { benchResults.add(fullRagCall("WARM-2")); }

    @Test @Order(40)
    void t1_warm3() { benchResults.add(fullRagCall("WARM-3")); }

    private BenchRun fullRagCall(String label) {
        chatModelSpy.reset();

        long retStart = System.nanoTime();
        ragRetrieval.search(appProperties.rag().sourceId(), CDQ_Q, 4, 0.0);
        long retMs = (System.nanoTime() - retStart) / 1_000_000;

        long wallStart = System.nanoTime();
        ChatApiResponse r = assistantService.ask(new ChatRequest(CDQ_Q));
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;

        List<String> sections = r.evidence().ragDocuments().stream()
            .map(e -> e.section()).filter(s -> s != null).distinct().toList();

        System.out.printf("  [%s] wall=%,d ms  llmCalls=%d  sumCallMs=%,d  inTok=%d  outTok=%d  retMs=%d%n",
            label, wallMs, chatModelSpy.calls.size(), chatModelSpy.sumCallMs(),
            chatModelSpy.totalInTok(), chatModelSpy.totalOutTok(), retMs);
        System.out.printf("    answer: %s%n", truncate(r.answer(), 120));
        System.out.printf("    sections: %s%n%n", sections);

        return new BenchRun(label, wallMs, chatModelSpy.calls.size(), chatModelSpy.sumCallMs(),
            chatModelSpy.totalInTok(), chatModelSpy.totalOutTok(), retMs, r.answer(), sections);
    }

    // ── Test 3: RAG quality assertions ────────────────────────────────────────

    @Test @Order(50)
    void t3_trust_score_en() {
        ChatApiResponse r = ask("What is the Trust Score in CDQ Fraud Guard?");
        assertThat(r.answer()).isNotBlank();
        assertEvidenceSection(r.evidence(), "trust score");
        assertActiveVersionId(r.evidence());
        qualityResults.add(qualityLine("Trust Score EN", r));
    }

    @Test @Order(60)
    void t3_bank_account_en() {
        ChatApiResponse r = ask("How does CDQ Fraud Guard verify bank accounts?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.answer().toLowerCase()).containsAnyOf("bank", "verif", "account");
        assertEvidenceSection(r.evidence(), "bank account");
        assertActiveVersionId(r.evidence());
        qualityResults.add(qualityLine("Bank Account EN", r));
    }

    @Test @Order(70)
    void t3_bank_account_pl() {
        ChatApiResponse r = ask("Jak CDQ Fraud Guard weryfikuje rachunki bankowe?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertEvidenceSection(r.evidence(), "bank account");
        qualityResults.add(qualityLine("Bank Account PL", r));
    }

    @Test @Order(80)
    void t3_trust_score_de() {
        ChatApiResponse r = ask("Wie funktioniert der Trust Score?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.evidence().ragDocuments()).isNotEmpty();
        assertEvidenceSection(r.evidence(), "trust score");
        qualityResults.add(qualityLine("Trust Score DE", r));
    }

    @Test @Order(90)
    void t3_no_hallucination() {
        ChatApiResponse r = ask("Does CDQ Fraud Guard support cryptocurrency payments?");
        assertThat(r.answer()).isNotBlank();
        assertThat(r.answer().toLowerCase())
            .doesNotContain("supports cryptocurrency")
            .doesNotContain("yes, cdq fraud guard supports crypto");
        qualityResults.add(qualityLine("No Hallucination", r));
    }

    // ── Test 4: Tool calling smoke test ───────────────────────────────────────

    @Test @Order(100)
    void t4_tool_call_smoke() {
        chatModelSpy.reset();

        String answer = ChatClient.builder(chatModelSpy)
            .build()
            .prompt()
            .system("You are a helpful assistant. Use available tools to answer questions about temperature.")
            .user("What is the current temperature in Warsaw?")
            .tools(tempTool)
            .call()
            .content();

        boolean toolInvoked = !tempTool.invocations.isEmpty();

        System.out.printf("%n  [T4] model=%s  llmCalls=%d  toolInvoked=%s%n",
            model, chatModelSpy.calls.size(), toolInvoked);
        System.out.printf("  answer: %s%n", truncate(answer, 150));
        System.out.printf("  invocations: %s%n%n", tempTool.invocations);

        assertThat(answer).isNotBlank();

        if (toolInvoked) {
            System.out.println("  [T4] PASS — tool was invoked; model used tool calling API correctly");
            assertThat(answer.toLowerCase()).containsAnyOf("22", "celsius", "degrees", "warsaw");
        } else {
            System.out.println("  [T4] INFO — tool was NOT invoked; model answered without tool calling");
            System.out.println("  [T4] This is a capability gap for " + model + " on this prompt");
        }
    }

    // ── Report ────────────────────────────────────────────────────────────────

    @AfterAll
    void printSummary() throws Exception {
        if (benchResults.isEmpty()) return;

        List<BenchRun> warm = benchResults.stream().filter(r -> r.label().startsWith("WARM")).toList();
        long warmAvgWall    = warm.isEmpty() ? 0 : (long) warm.stream().mapToLong(BenchRun::wallMs).average().orElse(0);
        long warmAvgSumCall = warm.isEmpty() ? 0 : (long) warm.stream().mapToLong(BenchRun::sumCallMs).average().orElse(0);
        double warmAvgOutTok = warm.isEmpty() ? 0 : warm.stream().mapToInt(r -> r.outTok() != null ? r.outTok() : 0).average().orElse(0);

        BenchRun cold = benchResults.stream().filter(r -> r.label().equals("COLD")).findFirst().orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("=".repeat(100)).append("\n");
        sb.append(String.format("  FULL PIPELINE BENCHMARK  |  model=%-30s  |  %s%n", model, java.time.LocalDateTime.now()));
        sb.append(String.format("  Question: \"%s\"%n", CDQ_Q));
        sb.append("=".repeat(100)).append("\n");

        sb.append(String.format("%-22s  %10s  %10s  %10s  %8s  %8s  %8s%n",
            "Run", "Wall (ms)", "SumCall(ms)", "LLM calls", "InTok", "OutTok", "Ret(ms)"));
        sb.append("-".repeat(90)).append("\n");
        for (BenchRun r : benchResults) {
            sb.append(String.format("%-22s  %,10d  %,10d  %10d  %8s  %8s  %,8d%n",
                r.label(), r.wallMs(), r.sumCallMs(), r.llmCalls(),
                r.inTok() != null ? r.inTok() : "N/A",
                r.outTok() != null ? r.outTok() : "N/A",
                r.retMs()));
        }
        sb.append("-".repeat(90)).append("\n");
        if (cold != null) {
            sb.append(String.format("%-22s  %,10d  %,10d  %10d  %8s  %8s  %,8d%n",
                "COLD", cold.wallMs(), cold.sumCallMs(), cold.llmCalls(),
                cold.inTok() != null ? cold.inTok() : "N/A",
                cold.outTok() != null ? cold.outTok() : "N/A",
                cold.retMs()));
        }
        if (!warm.isEmpty()) {
            sb.append(String.format("%-22s  %,10d  %,10d  %10s  %8s  %8.0f  %8s%n",
                "WARM avg", warmAvgWall, warmAvgSumCall, "-", "-", warmAvgOutTok, "-"));
        }

        sb.append("\n");
        sb.append("  LLM CALL COUNT ANALYSIS (Test 2):\n");
        for (BenchRun r : benchResults) {
            String detail = chatModelSpy.calls.isEmpty() ? "(spy cleared)" :
                r.llmCalls() + " call(s) — " + (r.llmCalls() > 1 ? "tool loop or multi-step advisor" : "single call as expected");
            sb.append(String.format("    %-12s  calls=%d  → %s%n", r.label(), r.llmCalls(), detail));
        }

        sb.append("\n");
        sb.append("  QUALITY TEST RESULTS (Test 3):\n");
        for (String q : qualityResults) {
            sb.append("    ").append(q).append("\n");
        }

        sb.append("\n");
        sb.append("  TOOL CALLING (Test 4):\n");
        sb.append("    model=").append(model)
          .append("  toolInvoked=").append(!tempTool.invocations.isEmpty())
          .append("  invocations=").append(tempTool.invocations).append("\n");

        if (!warm.isEmpty()) {
            long springAiOverhead = warmAvgWall - warmAvgSumCall;
            sb.append("\n");
            sb.append("  OVERHEAD ANALYSIS:\n");
            sb.append(String.format("    Warm avg wall:          %,d ms%n", warmAvgWall));
            sb.append(String.format("    Warm avg LLM call sum:  %,d ms%n", warmAvgSumCall));
            sb.append(String.format("    Spring AI overhead:     %,d ms  (advisors + embedding + pgvector)%n", springAiOverhead));
            sb.append(String.format("    Retrieval (separate):   %,d ms%n",
                warm.stream().mapToLong(BenchRun::retMs).min().orElse(0)));
        }

        sb.append("\n");
        sb.append("  ANSWER PREVIEWS:\n");
        for (BenchRun r : benchResults) {
            sb.append(String.format("    [%-8s] %s%n", r.label(), truncate(r.answer(), 100)));
        }

        sb.append("=".repeat(100)).append("\n");

        String report = sb.toString();
        System.out.println(report);
        Files.writeString(Paths.get("target/full-pipeline-report.txt"), report);
        System.out.println("Report written to target/full-pipeline-report.txt");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChatApiResponse ask(String q) {
        return assistantService.ask(new ChatRequest(q));
    }

    private void assertEvidenceSection(ExecutionEvidence ev, String keyword) {
        assertThat(ev.ragDocuments()).isNotEmpty();
        assertThat(ev.ragDocuments()).anyMatch(d ->
            d.section() != null && d.section().toLowerCase().contains(keyword.toLowerCase()));
    }

    private void assertActiveVersionId(ExecutionEvidence ev) {
        if (ev.ragDocuments().isEmpty()) return;
        Long activeVersionId = jdbcTemplate.queryForObject(
            "SELECT active_version_id FROM rag_source WHERE source_key = ?",
            Long.class, appProperties.rag().sourceId());
        assertThat(activeVersionId).isNotNull();
        ev.ragDocuments().forEach(d ->
            assertThat(d.sourceVersionId())
                .as("sourceVersionId should equal active version %d", activeVersionId)
                .isEqualTo(activeVersionId));
    }

    private String qualityLine(String label, ChatApiResponse r) {
        int docs = r.evidence().ragDocuments().size();
        String sections = r.evidence().ragDocuments().stream()
            .map(e -> e.section()).filter(s -> s != null).distinct()
            .reduce((a, b) -> a + ", " + b).orElse("-");
        return String.format("%-20s  docs=%-2d  sections=[%s]  answer: %s",
            label, docs, sections, truncate(r.answer(), 80));
    }

    private void unloadModel() {
        try {
            String body = String.format(
                "{\"model\":\"%s\",\"keep_alive\":0,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                model);
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            System.out.println("  [unload] model unloaded from VRAM");
            Thread.sleep(500);
        } catch (Exception e) {
            System.out.println("  [unload] warning: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean isOllamaRunning() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URI("http://localhost:11434").toURL().openConnection();
            c.setConnectTimeout(2_000);
            c.connect();
            return c.getResponseCode() >= 0;
        } catch (Exception e) { return false; }
    }

    private static boolean isModelAvailable(String modelName) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URI("http://localhost:11434/api/tags").toURL().openConnection();
            c.setConnectTimeout(2_000);
            c.connect();
            String body = new String(c.getInputStream().readAllBytes());
            return body.contains("\"" + modelName + "\"");
        } catch (Exception e) { return false; }
    }
}
