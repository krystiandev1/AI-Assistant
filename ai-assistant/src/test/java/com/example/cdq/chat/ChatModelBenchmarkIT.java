package com.example.cdq.chat;

import com.example.cdq.config.AppProperties;
import com.example.cdq.rag.RagRetrieval;
import com.example.cdq.rag.lifecycle.DocumentLifecycleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Direct Ollama performance benchmark: qwen3:4b vs qwen3:4b-instruct,
 * plus think-parameter control comparison for qwen3:4b.
 *
 * Scenarios:
 *   S1–S3: raw /api/chat calls (no Spring AI overhead)
 *   S4:    Spring AI AssistantService (wall-clock only; token detail via FullPipelineBenchmarkIT)
 *
 *   S1 — Simple direct: "What is 2+2?", no context
 *   S2 — CDQ, no RAG:   CDQ question + system prompt, no retrieved chunks
 *   S3 — CDQ, simulated RAG: CDQ question + system prompt + 4 retrieved chunks
 *
 * Thinking-control section (qwen3:4b only, separate from model comparison):
 *   think=true  greedy (temp=0.0)
 *   think=false greedy (temp=0.0)   — key: does Ollama actually suppress reasoning?
 *   think=true  recommended (temp=0.6, top_p=0.95, top_k=20)  — Qwen3 official recommendation
 *
 * Runtime model decision:
 *   qwen3:4b-instruct = default interactive model (OLLAMA_CHAT_MODEL=qwen3:4b-instruct)
 *   qwen3:4b          = strict-task compatibility option (OLLAMA_CHAT_MODEL=qwen3:4b)
 *
 * Guard: RUN_BENCHMARK=true
 * Run:   RUN_BENCHMARK=true ./mvnw verify -Pintegration -pl ai-assistant -Dit.test=ChatModelBenchmarkIT
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("chat-it")
@Tag("integration")
@Tag("benchmark")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatModelBenchmarkIT {

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

    // ── Injected beans ────────────────────────────────────────────────────────

    @Autowired DocumentLifecycleService lifecycleService;
    @Autowired AppProperties            appProperties;
    @Autowired RagRetrieval             ragRetrieval;
    @Autowired AssistantService         assistantService;

    @Value("${spring.ai.ollama.chat.model}")
    String configuredChatModel;

    @Value("classpath:prompts/system-prompt.st")
    Resource systemPromptResource;

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String OLLAMA_BASE    = "http://localhost:11434";
    private static final String MODEL_MAIN     = "qwen3:4b";
    private static final String MODEL_INSTRUCT = "qwen3:4b-instruct";
    private static final String SIMPLE_Q       = "What is 2+2?";
    private static final String CDQ_Q          = "What is the Trust Score in CDQ Fraud Guard?";
    private static final int    WARM_RUNS      = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Sampling parameter sets ───────────────────────────────────────────────

    /**
     * Encapsulates the Ollama request parameters that affect thinking/generation behaviour.
     * `think` is sent as a top-level field in the /api/chat request body.
     */
    record SamplingParams(boolean think, double temperature, Double topP, Integer topK) {

        /** Greedy decoding — matches application default (temperature=0.0). */
        static SamplingParams greedy(boolean think) {
            return new SamplingParams(think, 0.0, null, null);
        }

        /** Qwen3 official recommendation for thinking mode. */
        static SamplingParams qwen3Thinking() {
            return new SamplingParams(true, 0.6, 0.95, 20);
        }

        String label() {
            String s = "think=" + think + " temp=" + temperature;
            if (topP != null) s += " top_p=" + topP;
            if (topK != null) s += " top_k=" + topK;
            return s;
        }
    }

    // ── Data types ────────────────────────────────────────────────────────────

    /** Raw result from a single Ollama /api/chat call. */
    record OllamaResult(
        long   totalMs,
        Long   loadMs,
        Long   pEvalMs,
        Long   evalMs,
        int    inTok,
        int    outTok,
        double tokSec,
        String content,
        String thinking   // null = field absent in response; "" = field present but empty
    ) {}

    /** Aggregated row for model-comparison table (warm average of WARM_RUNS). */
    record Row(
        String  model,
        String  scenario,
        long    totalMs,
        Long    evalMs,
        int     inTok,
        int     outTok,
        double  tokSec,
        String  answer,
        int     avgThinkingLen  // avg chars in thinking field; 0 = none
    ) {}

    /** Single-variant result for thinking-control table. */
    record ThinkRow(
        String  variant,
        long    totalMs,
        Long    evalMs,
        int     inTok,
        int     outTok,
        double  tokSec,
        boolean thinkingPresent,
        int     avgThinkingLen,
        boolean contentLeaksReasoning,
        String  answer
    ) {}

    // ── State ─────────────────────────────────────────────────────────────────

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    private String  systemPromptText;
    private String  ragContextBlock;
    private boolean instructAvailable;

    private final List<Row>      rows      = new ArrayList<>();
    private final List<ThinkRow> thinkRows = new ArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeAll
    void setup() throws Exception {
        assumeTrue(System.getenv("RUN_BENCHMARK") != null,
            "Benchmark skipped — set env var RUN_BENCHMARK=true");
        assumeTrue(isModelAvailable(MODEL_MAIN), MODEL_MAIN + " not pulled");

        instructAvailable = isModelAvailable(MODEL_INSTRUCT);
        System.out.printf("Models: %s ✓  |  %s %s%n",
            MODEL_MAIN, MODEL_INSTRUCT, instructAvailable ? "✓" : "✗ (instruct scenarios will be skipped)");
        System.out.printf("Spring AI configured model: %s%n", configuredChatModel);
        System.out.printf("WARM_RUNS: %d (1 warmup discarded + %d measured, average reported)%n%n",
            WARM_RUNS, WARM_RUNS);

        systemPromptText = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);

        lifecycleService.synchronize(appProperties.rag().sourceId());

        List<Document> chunks = ragRetrieval.search(
            appProperties.rag().sourceId(), CDQ_Q, 4, 0.0);
        System.out.printf("Retrieved %d CDQ chunks (used for S3 and thinking-control)%n%n", chunks.size());

        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String section = (String) chunks.get(i).getMetadata().getOrDefault("section", "context");
            ctx.append("\n--- Chunk ").append(i + 1)
               .append(" [").append(section).append("] ---\n")
               .append(chunks.get(i).getText()).append("\n");
        }
        ragContextBlock = ctx.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Thinking-control: qwen3:4b only
    // Determines whether think=false actually suppresses reasoning in current Ollama build.
    // Uses S3-equivalent prompt (system + 4 chunks + CDQ question).
    // ══════════════════════════════════════════════════════════════════════════

    @Test @Order(5)
    void think_control_greedy_think_true() throws Exception {
        log("── think-control  qwen3:4b  think=true  temp=0.0  (greedy baseline) ──");
        thinkRows.add(thinkRun("think=true  greedy  (temp=0.0)",
            SamplingParams.greedy(true)));
    }

    @Test @Order(6)
    void think_control_greedy_think_false() throws Exception {
        log("── think-control  qwen3:4b  think=false  temp=0.0 ──");
        thinkRows.add(thinkRun("think=false greedy  (temp=0.0)",
            SamplingParams.greedy(false)));
    }

    @Test @Order(7)
    void think_control_recommended_think_true() throws Exception {
        log("── think-control  qwen3:4b  think=true  temp=0.6 top_p=0.95 top_k=20  (Qwen3 recommended) ──");
        thinkRows.add(thinkRun("think=true  recommended (temp=0.6 top_p=0.95 top_k=20)",
            SamplingParams.qwen3Thinking()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Model comparison: qwen3:4b  (S1–S4)
    // ══════════════════════════════════════════════════════════════════════════

    @Test @Order(10)
    void main_s1_simple() throws Exception {
        log("── qwen3:4b  S1: Simple direct (2+2) ──");
        rows.add(run(MODEL_MAIN, "S1 simple direct", null, SIMPLE_Q, SamplingParams.greedy(true)));
    }

    @Test @Order(20)
    void main_s2_cdq_no_rag() throws Exception {
        log("── qwen3:4b  S2: CDQ, no context ──");
        rows.add(run(MODEL_MAIN, "S2 CDQ, no context", systemPromptText, CDQ_Q, SamplingParams.greedy(true)));
    }

    @Test @Order(30)
    void main_s3_cdq_simulated_rag() throws Exception {
        log("── qwen3:4b  S3: CDQ, simulated RAG (system + 4 chunks) ──");
        rows.add(run(MODEL_MAIN, "S3 CDQ, simulated RAG",
            systemPromptText, contextUserMsg(CDQ_Q), SamplingParams.greedy(true)));
    }

    /**
     * S4: full Spring AI application path (wall-clock only).
     * Uses the model configured via OLLAMA_CHAT_MODEL (logged at startup).
     * Token detail is available via ChatModelSpy in FullPipelineBenchmarkIT.
     * The difference between S3 and S4 reflects different prompt contents
     * (advisor-generated vs manually assembled), not Spring AI framework overhead.
     */
    @Test @Order(40)
    void main_s4_spring_ai_actual() {
        log("── S4: Spring AI AssistantService  model=" + configuredChatModel + " ──");

        long t0 = now();
        assistantService.ask(new ChatRequest(CDQ_Q));
        System.out.printf("  [warmup] %,d ms%n", elapsed(t0));

        long t1 = now();
        ChatApiResponse r = assistantService.ask(new ChatRequest(CDQ_Q));
        long ms = elapsed(t1);
        System.out.printf("  [run 1] total=%,d ms  (token detail: use FullPipelineBenchmarkIT)%n", ms);
        System.out.printf("  answer: %s%n%n", truncate(r.answer(), 120));

        rows.add(new Row(configuredChatModel + " (Spring AI)", "S4 Spring AI actual",
            ms, null, 0, 0, 0, truncate(r.answer(), 90), 0));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Model comparison: qwen3:4b-instruct  (S1–S3)
    // ══════════════════════════════════════════════════════════════════════════

    @Test @Order(50)
    void instruct_warmup_and_switch() throws Exception {
        assumeTrue(instructAvailable, MODEL_INSTRUCT + " not pulled — skipping instruct scenarios");
        log("── Unloading " + MODEL_MAIN + ", loading " + MODEL_INSTRUCT + " ──");
        unload(MODEL_MAIN);
    }

    @Test @Order(60)
    void instruct_s1_simple() throws Exception {
        assumeTrue(instructAvailable, MODEL_INSTRUCT + " not available");
        log("── qwen3:4b-instruct  S1: Simple direct (2+2) ──");
        rows.add(run(MODEL_INSTRUCT, "S1 simple direct", null, SIMPLE_Q, SamplingParams.greedy(false)));
    }

    @Test @Order(70)
    void instruct_s2_cdq_no_rag() throws Exception {
        assumeTrue(instructAvailable, MODEL_INSTRUCT + " not available");
        log("── qwen3:4b-instruct  S2: CDQ, no context ──");
        rows.add(run(MODEL_INSTRUCT, "S2 CDQ, no context", systemPromptText, CDQ_Q, SamplingParams.greedy(false)));
    }

    @Test @Order(80)
    void instruct_s3_cdq_simulated_rag() throws Exception {
        assumeTrue(instructAvailable, MODEL_INSTRUCT + " not available");
        log("── qwen3:4b-instruct  S3: CDQ, simulated RAG (system + 4 chunks) ──");
        rows.add(run(MODEL_INSTRUCT, "S3 CDQ, simulated RAG",
            systemPromptText, contextUserMsg(CDQ_Q), SamplingParams.greedy(false)));
    }

    // ── Core: run warmup + WARM_RUNS measured calls ────────────────────────────

    private Row run(String model, String scenario, String sysMsg, String userMsg, SamplingParams params)
            throws Exception {
        OllamaResult warmup = ollamaCall(model, sysMsg, userMsg, params);
        System.out.printf("  [warmup] total=%,d ms  outTok=%d  tok/s=%.1f%n",
            warmup.totalMs(), warmup.outTok(), warmup.tokSec());

        long   sumTotal = 0, sumEval = 0;
        int    sumIn = 0, sumOut = 0, sumThinkingLen = 0;
        double sumTokSec = 0;
        String lastAnswer = "";

        for (int i = 0; i < WARM_RUNS; i++) {
            OllamaResult r = ollamaCall(model, sysMsg, userMsg, params);
            sumTotal       += r.totalMs();
            sumEval        += r.evalMs() != null ? r.evalMs() : 0;
            sumIn          += r.inTok();
            sumOut         += r.outTok();
            sumThinkingLen += r.thinking() != null ? r.thinking().length() : 0;
            sumTokSec      += r.tokSec();
            lastAnswer      = r.content();
            System.out.printf(
                "  [run %d] total=%,d ms  load=%s  pEval=%s  eval=%s  in=%d  out=%d  tok/s=%.1f  thinking=%s%n",
                i + 1, r.totalMs(),
                r.loadMs()  != null ? r.loadMs()  + "ms" : "-",
                r.pEvalMs() != null ? r.pEvalMs() + "ms" : "-",
                r.evalMs()  != null ? r.evalMs()  + "ms" : "-",
                r.inTok(), r.outTok(), r.tokSec(),
                r.thinking() != null ? r.thinking().length() + " chars" : "none");
            System.out.printf("  answer: %s%n%n", truncate(r.content(), 130));
        }

        return new Row(model, scenario,
            sumTotal / WARM_RUNS,
            sumEval > 0 ? sumEval / WARM_RUNS : null,
            sumIn / WARM_RUNS, sumOut / WARM_RUNS, sumTokSec / WARM_RUNS,
            truncate(lastAnswer, 90),
            sumThinkingLen / WARM_RUNS);
    }

    /**
     * Single think-control run: warmup + WARM_RUNS measured, with explicit thinking field reporting.
     * Always uses qwen3:4b with S3-equivalent context (system prompt + 4 RAG chunks).
     */
    private ThinkRow thinkRun(String variant, SamplingParams params) throws Exception {
        OllamaResult warmup = ollamaCall(MODEL_MAIN, systemPromptText, contextUserMsg(CDQ_Q), params);
        System.out.printf("  [warmup] total=%,d ms  outTok=%d  thinking=%s%n",
            warmup.totalMs(), warmup.outTok(),
            warmup.thinking() != null ? warmup.thinking().length() + " chars" : "none");

        long   sumTotal = 0, sumEval = 0;
        int    sumIn = 0, sumOut = 0, sumThinkingLen = 0;
        double sumTokSec = 0;
        boolean thinkingPresent = false;
        boolean contentLeaks    = false;
        String  lastContent = "";

        for (int i = 0; i < WARM_RUNS; i++) {
            OllamaResult r = ollamaCall(MODEL_MAIN, systemPromptText, contextUserMsg(CDQ_Q), params);
            sumTotal  += r.totalMs();
            sumEval   += r.evalMs() != null ? r.evalMs() : 0;
            sumIn     += r.inTok();
            sumOut    += r.outTok();
            sumTokSec += r.tokSec();
            lastContent = r.content();

            if (r.thinking() != null && !r.thinking().isBlank()) {
                thinkingPresent = true;
                sumThinkingLen += r.thinking().length();
            }
            if (r.content().contains("<think>") || r.content().contains("</think>")) {
                contentLeaks = true;
            }

            System.out.printf(
                "  [run %d] total=%,d ms  eval=%s  in=%d  out=%d  tok/s=%.1f%n",
                i + 1, r.totalMs(),
                r.evalMs() != null ? r.evalMs() + "ms" : "-",
                r.inTok(), r.outTok(), r.tokSec());
            System.out.printf("  thinking field: %s%n",
                r.thinking() != null && !r.thinking().isBlank()
                    ? "PRESENT (" + r.thinking().length() + " chars): " + truncate(r.thinking(), 80)
                    : "absent or empty");
            System.out.printf("  content:        %s%n%n", truncate(r.content(), 130));
        }

        return new ThinkRow(variant,
            sumTotal / WARM_RUNS,
            sumEval > 0 ? sumEval / WARM_RUNS : null,
            sumIn / WARM_RUNS, sumOut / WARM_RUNS, sumTokSec / WARM_RUNS,
            thinkingPresent, thinkingPresent ? sumThinkingLen / WARM_RUNS : 0,
            contentLeaks, truncate(lastContent, 90));
    }

    // ── Ollama HTTP call ──────────────────────────────────────────────────────

    /**
     * Sends a single /api/chat request to Ollama.
     * `think` is sent as a top-level field in the request body (not inside `options`).
     * HTTP errors cause an assertion failure with status code and response body.
     */
    private OllamaResult ollamaCall(String model, String sysMsg, String userMsg, SamplingParams params)
            throws Exception {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model);
        req.put("stream", false);
        req.put("think", params.think());

        ObjectNode opts = MAPPER.createObjectNode();
        opts.put("temperature", params.temperature());
        if (params.topP() != null) opts.put("top_p", params.topP());
        if (params.topK() != null) opts.put("top_k", params.topK());
        req.set("options", opts);

        ArrayNode messages = MAPPER.createArrayNode();
        if (sysMsg != null && !sysMsg.isBlank()) {
            messages.add(MAPPER.createObjectNode()
                .put("role", "system").put("content", sysMsg));
        }
        messages.add(MAPPER.createObjectNode()
            .put("role", "user").put("content", userMsg));
        req.set("messages", messages);

        String bodyStr = MAPPER.writeValueAsString(req);
        System.out.printf("  [request] model=%s  %s%n", model, params.label());

        long wallStart = now();
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(15))
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        long totalMs = elapsed(wallStart);

        if (resp.statusCode() != 200) {
            fail("Ollama returned HTTP " + resp.statusCode()
                + " for model=" + model + "  " + params.label()
                + "\nbody: " + resp.body().substring(0, Math.min(400, resp.body().length())));
        }

        JsonNode json   = MAPPER.readTree(resp.body());
        JsonNode msgNode = json.path("message");

        int    outTok  = json.path("eval_count").asInt(0);
        long   evalNs  = json.path("eval_duration").asLong(0);
        long   loadNs  = json.path("load_duration").asLong(0);
        long   pEvalNs = json.path("prompt_eval_duration").asLong(0);
        int    inTok   = json.path("prompt_eval_count").asInt(0);
        double tokSec  = (outTok > 0 && evalNs > 0) ? outTok / (evalNs / 1e9) : 0;

        String  content  = msgNode.path("content").asText("");
        JsonNode thNode  = msgNode.path("thinking");
        String  thinking = thNode.isMissingNode() ? null : thNode.asText("");

        return new OllamaResult(totalMs,
            loadNs  > 0 ? loadNs  / 1_000_000 : null,
            pEvalNs > 0 ? pEvalNs / 1_000_000 : null,
            evalNs  > 0 ? evalNs  / 1_000_000 : null,
            inTok, outTok, tokSec, content, thinking);
    }

    // ── Summary report ────────────────────────────────────────────────────────

    @AfterAll
    void printSummary() {
        List<String> scenarios = List.of(
            "S1 simple direct", "S2 CDQ, no context", "S3 CDQ, simulated RAG", "S4 Spring AI actual");

        String bar  = "=".repeat(160);
        String dash = "-".repeat(160);
        StringBuilder sb = new StringBuilder();

        // ── Model comparison table ──
        sb.append("\n").append(bar).append("\n");
        sb.append("  MODEL COMPARISON: qwen3:4b  vs  qwen3:4b-instruct\n");
        sb.append("  Question: \"").append(CDQ_Q).append("\"\n");
        sb.append("  WARM_RUNS=").append(WARM_RUNS).append("  (warm average reported)\n");
        sb.append(bar).append("\n");
        sb.append(String.format("%-28s │ %-60s │ %-60s │ %s%n",
            "Scenario",
            "qwen3:4b (total / eval / in / out / tok/s)",
            "qwen3:4b-instruct (total / eval / in / out / tok/s)",
            "Δ total"));
        sb.append(dash).append("\n");

        for (String sc : scenarios) {
            Row main     = findRow(MODEL_MAIN, sc)
                .or(() -> rows.stream()
                    .filter(r -> r.scenario().equals(sc) && r.model().contains("Spring AI"))
                    .findFirst())
                .orElse(null);
            Row instruct = findRow(MODEL_INSTRUCT, sc).orElse(null);

            String mainCol  = main     != null ? rowSummary(main)     : "—";
            String instrCol = instruct != null ? rowSummary(instruct)
                : (sc.equals("S4 Spring AI actual") ? "N/A — see FullPipelineBenchmarkIT" : "NOT PULLED");
            String delta    = (main != null && instruct != null)
                ? String.format("%+,d ms (%+d tok)",
                    instruct.totalMs() - main.totalMs(),
                    instruct.outTok()  - main.outTok())
                : "—";

            sb.append(String.format("%-28s │ %-60s │ %-60s │ %s%n", sc, mainCol, instrCol, delta));
        }

        // Answer previews
        sb.append(bar).append("\n  QUALITY — answer previews:\n");
        for (Row r : rows) {
            sb.append(String.format("  [%-28s | %-24s]: %s%n", r.scenario(), r.model(), r.answer()));
        }

        // ── Thinking-control table ──
        if (!thinkRows.isEmpty()) {
            sb.append("\n").append(bar).append("\n");
            sb.append("  THINKING CONTROL: qwen3:4b  (CDQ question + simulated RAG context)\n");
            sb.append("  WARM_RUNS=").append(WARM_RUNS).append("\n");
            sb.append(bar).append("\n");
            sb.append(String.format("%-44s │ %10s │ %10s │ %5s │ %5s │ %7s │ %-18s │ %-14s%n",
                "Variant", "Total(ms)", "Eval(ms)", "InTok", "OutTok", "Tok/s",
                "thinking field", "content leaks?"));
            sb.append(dash).append("\n");
            for (ThinkRow tr : thinkRows) {
                sb.append(String.format("%-44s │ %,10d │ %10s │ %5d │ %5d │ %7.1f │ %-18s │ %-14s%n",
                    tr.variant(),
                    tr.totalMs(),
                    tr.evalMs() != null ? String.format("%,d", tr.evalMs()) : "-",
                    tr.inTok(), tr.outTok(), tr.tokSec(),
                    tr.thinkingPresent() ? "present (~" + tr.avgThinkingLen() + "c)" : "absent/empty",
                    tr.contentLeaksReasoning() ? "YES — <think> tag!" : "no"));
            }
            sb.append("\n  Think-control answer previews:\n");
            for (ThinkRow tr : thinkRows) {
                sb.append(String.format("  [%s]: %s%n", tr.variant(), tr.answer()));
            }
        }

        // ── Analysis ──
        sb.append("\n").append(bar).append("\n  ANALYSIS:\n\n");

        var s3main    = findRow(MODEL_MAIN,     "S3 CDQ, simulated RAG");
        var s3instruct = findRow(MODEL_INSTRUCT, "S3 CDQ, simulated RAG");
        var s2main    = findRow(MODEL_MAIN,     "S2 CDQ, no context");
        var s2instruct = findRow(MODEL_INSTRUCT, "S2 CDQ, no context");

        s3main.ifPresent(m -> sb.append(String.format(
            "    qwen3:4b     S3 (simulated RAG, warm avg): %,5d ms  │  %4d output tokens%n",
            m.totalMs(), m.outTok())));
        s3instruct.ifPresent(i -> {
            sb.append(String.format(
                "    qwen3:4b-ins S3 (simulated RAG, warm avg): %,5d ms  │  %4d output tokens%n",
                i.totalMs(), i.outTok()));
            s3main.ifPresent(m -> sb.append(String.format(
                "    → instruct saves: %,d ms  |  %d output tokens  |  %.2fx speedup%n",
                m.totalMs() - i.totalMs(),
                m.outTok()  - i.outTok(),
                m.totalMs() > 0 ? (double) m.totalMs() / i.totalMs() : 0)));
        });

        sb.append("\n    S2 (CDQ, no context) — token cost of unrestricted reasoning without grounding:\n");
        s2main.ifPresent(m -> sb.append(String.format(
            "    qwen3:4b:     %,5d ms  │  %4d tokens%n", m.totalMs(), m.outTok())));
        s2instruct.ifPresent(i -> sb.append(String.format(
            "    qwen3:4b-ins: %,5d ms  │  %4d tokens%n", i.totalMs(), i.outTok())));

        sb.append("""

    S4 vs S3 NOTE:
    The difference in wall time between S3 (manually assembled prompt) and S4 (Spring AI path)
    reflects different prompt contents — the full advisor-generated prompt correlates with
    substantially higher reasoning-token generation. This is not Spring AI framework overhead.
    True infrastructure overhead (~74–111 ms measured via ChatModelSpy) is reported separately
    in FullPipelineBenchmarkIT.
""");

        // ── Recommendations ──
        sb.append("  RECOMMENDATIONS:\n");
        sb.append("    * qwen3:4b-instruct = default interactive model (OLLAMA_CHAT_MODEL=qwen3:4b-instruct)\n");
        sb.append("    * qwen3:4b          = strict-task compatibility option (OLLAMA_CHAT_MODEL=qwen3:4b)\n");
        s3instruct.ifPresent(i -> s3main.ifPresent(m -> {
            if (i.totalMs() < m.totalMs() * 0.7)
                sb.append("    * qwen3:4b-instruct shows significant speedup (>30%) — preferred for interactive use\n");
        }));
        if (!thinkRows.isEmpty()) {
            boolean anyThinkFalseWorked = thinkRows.stream()
                .filter(r -> r.variant().contains("think=false"))
                .anyMatch(r -> !r.thinkingPresent() && r.outTok() < 200);
            if (anyThinkFalseWorked)
                sb.append("    * think=false: appears to suppress reasoning in this run\n");
            else
                sb.append("    * think=false: see thinking-control table — check whether reasoning was actually suppressed\n");
        }
        sb.append("    * /no_think in user message: showed +163% output tokens (worse) in prior benchmark\n");
        sb.append("    * num_predict cap: viable safety net to limit runaway reasoning blocks\n");

        sb.append(bar).append("\n");

        System.out.println(sb);
        try {
            Files.writeString(Paths.get("target/model-comparison-report.txt"), sb.toString());
            System.out.println("Report: target/model-comparison-report.txt");
        } catch (Exception e) {
            System.err.println("Could not write report: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Optional<Row> findRow(String model, String scenario) {
        return rows.stream()
            .filter(r -> r.model().equals(model) && r.scenario().equals(scenario))
            .findFirst();
    }

    private static String rowSummary(Row r) {
        if (r.evalMs() == null)
            return String.format("%,d ms (wall-clock only)", r.totalMs());
        return String.format("%,d ms / %,d ms / %d / %d / %.1f",
            r.totalMs(), r.evalMs(), r.inTok(), r.outTok(), r.tokSec());
    }

    private String contextUserMsg(String question) {
        return "Context:\n" + ragContextBlock + "\nQuestion: " + question;
    }

    private void unload(String model) throws Exception {
        String body = "{\"model\": \"" + model + "\", \"keep_alive\": 0}";
        http.send(HttpRequest.newBuilder()
            .uri(URI.create(OLLAMA_BASE + "/api/generate"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.discarding());
        Thread.sleep(2_000);
    }

    private static void   log(String msg)   { System.out.println("\n" + msg); }
    private static long   now()             { return System.nanoTime(); }
    private static long   elapsed(long s)   { return (System.nanoTime() - s) / 1_000_000; }

    private static String truncate(String s, int max) {
        if (s == null || s.isBlank()) return "-";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static boolean isModelAvailable(String name) {
        try {
            var c = (java.net.HttpURLConnection)
                new URI(OLLAMA_BASE + "/api/tags").toURL().openConnection();
            c.setConnectTimeout(2_000); c.connect();
            return new String(c.getInputStream().readAllBytes()).contains("\"" + name + "\"");
        } catch (Exception e) { return false; }
    }
}
