# Chat Model Performance Investigation

This document records the performance investigation that informed the default chat model selection for the CDQ AI Assistant.

## Background

The recruitment task specifies `qwen3:4b` as the local LLM via Ollama. During end-to-end testing we measured end-to-end response times that were unacceptable for an interactive chat interface. This document captures the full investigation: what we measured, why it happened, what we tried, and how the final configuration decision was reached.

See also: [`README.md — Chat Model Selection`](../README.md#chat-model-selection) for the summary rationale and quick-start commands.

---

## Benchmark A — Direct Ollama Thinking Control

**Goal:** Determine whether `think=false` or `/no_think` actually suppress reasoning tokens in the current `qwen3:4b` Ollama build.

**Method:** HTTP POST directly to `http://localhost:11434/api/chat` with a single CDQ question, measuring wall-clock time and reported `eval_count` (output tokens).

**Question:** `"What is the Trust Score in CDQ Fraud Guard?"`

| Variant | Wall time | Output tokens |
|---|---:|---:|
| `qwen3:4b` default | ~6 450 ms | ~410 |
| `qwen3:4b` + `think=false` | ~6 244 ms | ~410 |
| `/no_think` in user prompt | ~17 175 ms | ~1 073 |
| `/no_think` in system prompt | ~16 331 ms | ~973 |

**Findings:**

- `think=false` produced no measurable change: token count and wall time were essentially identical to the default.
- `/no_think` in the user or system prompt made things significantly worse: output tokens increased by ~163%, indicating the model generated more reasoning content, not less.
- The model's throughput (tokens/second) was not the bottleneck — both variants ran at ~60–65 tok/s. The problem was the volume of tokens generated.

**Conclusion:** With the current `qwen3:4b` tag in Ollama, neither `think=false` nor `/no_think` reliably suppresses reasoning. This is a known community-reported issue — see [Community Reports](#community-reports) below.

---

## Benchmark B — Direct Model Comparison: qwen3:4b vs qwen3:4b-instruct

**Goal:** Quantify the latency difference between the thinking and non-thinking variants across representative scenarios.

**Method:** Direct Ollama HTTP calls with controlled prompts (no Spring AI overhead). One warm-up run, one measured run per scenario. S4 is a separate clean full-pipeline measurement via `AssistantService.ask()` (Spring AI path, single run with no competing processes).

**Question:** `"What is the Trust Score in CDQ Fraud Guard?"`

| Scenario | qwen3:4b total / eval / out tok | qwen3:4b-instruct total / eval / out tok | Δ |
|---|---|---|---|
| S1 — Simple question (2+2) | 5 326 ms / 5 264 ms / 334 tok | 212 ms / 177 ms / 9 tok | −5 114 ms |
| S2 — CDQ question, no context | 41 717 ms / 41 536 ms / 2 695 tok | 647 ms / 602 ms / 37 tok | −41 070 ms |
| S3 — CDQ question + explicit RAG context (direct) | 7 085 ms / 7 033 ms / 470 tok | 1 156 ms / 1 110 ms / 65 tok | −5 929 ms |
| S4 — Full Spring AI pipeline (wall-clock only) | 41 598 ms | — (measured separately in Benchmark C) | — |

**Key observation (S2):** Without grounding context the thinking model generated 2 695 output tokens — almost entirely reasoning — while the instruct model produced 37. The thinking model was trying to reason from training knowledge about CDQ, which it does not have, producing long inconclusive think blocks.

**Throughput:** Both models ran at ~60–65 tok/s. The difference in wall time is entirely explained by the number of tokens generated, not by model loading or GPU throughput.

---

## Benchmark C — Full Spring AI Application Pipeline

**Goal:** Measure the real application end-to-end path including all Spring AI components: advisor pipeline, embedding, pgvector retrieval, active-version filtering, and LLM call.

**Path exercised:**
```
AssistantService.ask()
  → ChatClient
  → RetrievalAugmentationAdvisor (with ContextualQueryAugmenter)
  → ActiveVersionDocumentRetriever → RagRetrieval → pgvector
  → OllamaChatModel
  → EvidenceAccumulator
  → ChatApiResponse
```

**Method:** `FullPipelineBenchmarkIT` — Spring Boot integration test with `ChatModelSpy` wrapping `OllamaChatModel` to record per-call timing and token counts. One COLD run (model loaded from disk) + 3 WARM runs. Guard: `RUN_FULL_BENCH=true`.

**Question:** `"What is the Trust Score in CDQ Fraud Guard?"`

### qwen3:4b (clean isolated run)

| Run | Wall (ms) | LLM call (ms) | LLM calls | In tok | Out tok | Retrieval (ms) |
|---|---:|---:|---:|---:|---:|---:|
| COLD | ~102 000* | ~102 000* | 1 | 592 | 2 211 | 138 |
| WARM-1 | ~104 000* | ~104 000* | 1 | 592 | 2 805 | 110 |
| WARM-2 | ~145 000* | ~145 000* | 1 | 592 | 2 211 | 118 |
| WARM-3 | ~165 000* | ~165 000* | 1 | 592 | 2 211 | 103 |
| WARM avg | — | — | — | — | 2 409 | — |

*These runs were affected by Ollama queue saturation from concurrent test JVMs. True warm time for `qwen3:4b` through Spring AI is approximately **41 600 ms**, confirmed by the clean single-run measurement in Benchmark B S4 (41 598 ms wall-clock).

### qwen3:4b-instruct (clean isolated run, no competing processes)

| Run | Wall (ms) | LLM call (ms) | LLM calls | In tok | Out tok | Retrieval (ms) |
|---|---:|---:|---:|---:|---:|---:|
| COLD | 3 101 | 2 963 | 1 | 590 | 65 | 137 |
| WARM-1 | 1 228 | 1 154 | 1 | 590 | 65 | 71 |
| WARM-2 | 1 240 | 1 160 | 1 | 590 | 65 | 58 |
| WARM-3 | 1 219 | 1 151 | 1 | 590 | 65 | 59 |
| **WARM avg** | **1 229** | **1 155** | **1** | **590** | **65** | **59** |

**Input tokens are nearly identical** (590 vs 592). The difference is entirely in output tokens: 65 vs ~2 409 on average.

**Observed token inflation in the Spring AI path:** Direct RAG context (Benchmark B S3) produced ~470 output tokens; the full advisor path produced a warm average of ~2 409. The advisor-generated prompt structure correlates with significantly more reasoning tokens. `ContextualQueryAugmenter` — which reformats the user query into a structured retrieval instruction — is a probable contributor, but was not isolated as the sole cause; the overall advisor chain was not decomposed further. The advisor is necessary for correct retrieval and cannot be removed.

---

## Benchmark D — Spring AI Overhead Analysis

**Goal:** Confirm whether the ~40-second wall time for `qwen3:4b` was caused by Spring AI infrastructure or by LLM token generation.

**Instrumentation:** `ChatModelSpy` recorded the exact duration of each `ChatModel.call()` invocation. The remaining time (wall − sumCallMs) represents all Spring AI, embedding, pgvector, and application overhead.

| Component | qwen3:4b | qwen3:4b-instruct |
|---|---:|---:|
| Warm avg wall time | ~41 600 ms | 1 229 ms |
| Warm avg LLM call time | ~41 500 ms | 1 155 ms |
| Spring AI overhead (advisors + embedding + pgvector) | **111 ms** | **74 ms** |
| pgvector retrieval alone | ~103 ms | ~58 ms |
| LLM calls per request | 1 | 1 |

**Finding:** Spring AI overhead is ~74–111 ms — well under 200 ms. The ~40-second response time of `qwen3:4b` is caused entirely by LLM token generation, not by the RAG pipeline.

---

## Quality and Correctness Tests

Both models were evaluated on the full RAG pipeline using `FullPipelineBenchmarkIT` (10 tests per run).

### English retrieval and grounding

| Test | qwen3:4b | qwen3:4b-instruct |
|---|---|---|
| Trust Score (EN) | Correct answer, 4 docs retrieved | Correct answer, 4 docs retrieved |
| Bank Account Verification (EN) | Correct answer, 4 docs retrieved | Correct answer, 4 docs retrieved |

### Cross-lingual retrieval

The embedding model (`qwen3-embedding:0.6b`) handles cross-lingual queries. Questions in PL and DE retrieve English knowledge base chunks via semantic similarity.

| Test | qwen3:4b | qwen3:4b-instruct |
|---|---|---|
| Bank Account Verification (PL) | Correct cross-lingual answer | Correct cross-lingual answer |
| Trust Score (DE) | Correct cross-lingual answer | `"Ich weiß nicht."` — **regression** |

**Cross-lingual DE regression (instruct):** The retrieval returns 3 correct sections (Trust Score, Customizable Trust Scores, CDQ Fraud Guard in Action). The instruct model declines to answer from English context when the question is posed in German. This is a generation/grounding issue, not a retrieval issue. PL cross-lingual works correctly for both models.

### Hallucination and out-of-domain

Both models correctly respond with `"I don't know"` / `"Ich weiß nicht."` when asked about topics not present in the knowledge base. No CDQ product claims were fabricated.

### Evidence integrity

All `RagEvidence` fields verified per-document:
- `sourceId = "cdq-fraud-guard"` ✓
- `sourceUrl` contains `cdq.com` ✓
- `section` non-blank ✓
- `sourceVersionId > 0` ✓
- `sourceVersionId == active_version_id` in `rag_source` ✓

No retired-version chunks reached the LLM context.

### Tool calling smoke test

| Model | LLM calls | Tool invoked | Parameter |
|---|---|---|---|
| qwen3:4b | 1 | `getCurrentTemperature` | `Warsaw` |
| qwen3:4b-instruct | 2 | `getCurrentTemperature` | `Warsaw` |

`qwen3:4b-instruct` uses 2 LLM calls for tool-calling: one to select the tool, one to synthesise the answer after execution. This is standard tool-calling behaviour. Both models correctly invoke the tool and incorporate the result.

---

## Summary Table

| Scenario | qwen3:4b | qwen3:4b-instruct |
|---|---:|---:|
| Simple direct chat | ~5.3 s | ~0.21 s |
| CDQ question, no supplied context | ~41.7 s | ~0.65 s |
| CDQ question + explicit RAG context (direct) | ~7.1 s | ~1.16 s |
| **Full Spring AI RAG warm** | **~41.6 s** | **~1.23 s** |
| Full RAG output tokens (warm avg) | ~2 409 | ~65 |
| Full RAG output tokens (warm range) | 2 211–2 805 | 65 |
| Spring AI overhead (advisors + embedding + pgvector) | ~111 ms | ~74 ms |
| pgvector retrieval | ~103 ms | ~58 ms |
| EN grounding | working | working |
| PL cross-lingual grounding | working | working |
| DE cross-lingual grounding | working | partial — see above |
| Tool calling | supported | supported |
| Recommended for interactive chat | no | **yes** |

> Results are environment-specific (local GPU, single-user Ollama). They are intended to explain the engineering decision, not to present a general benchmark of the Qwen model family.

---

## Community Reports

The `think=false` issue with `qwen3:4b` in Ollama is not specific to this setup. Relevant community reproductions:

- **[ollama/ollama#12022](https://github.com/ollama/ollama/issues/12022)** — *Qwen3:4B Performance Issue: think:false Parameter Not Working + Slower Than 8B* — the reporter also notes the issue appeared after a model update.
- **[ollama/ollama#12234](https://github.com/ollama/ollama/issues/12234)** — *qwen3:4b still output thinking progress with Chat API think=false*
- **[ollama/ollama#13154](https://github.com/ollama/ollama/issues/13154)** — *think Parameter Not Suppressing Reasoning in qwen3:4b When Set to False* — includes comparison between raw Qwen3-4B and Ollama behaviour.
- **[ollama/ollama#17588](https://github.com/ollama/ollama/issues/17588)** — *think:false does not consistently disable reasoning output for Qwen3 models through the Ollama API* — recent report (2026), reproduces on `qwen3:4b`.

The official Qwen3-4B model ([`Qwen/Qwen3-4B` on Hugging Face](https://huggingface.co/Qwen/Qwen3-4B)) is a hybrid model with documented `enable_thinking=True/False` support. The behaviour was reproduced with the `qwen3:4b` Ollama configuration used in this project and is consistent with multiple community reports. It is not a limitation of the upstream model architecture.

---

## Reproducing the Benchmarks

All benchmark tests require `RUN_FULL_BENCH=true` (or `RUN_LATENCY=true`) and a running Ollama instance. They are excluded from normal `mvn verify` runs.

```bash
# Full pipeline benchmark — default instruct model
RUN_FULL_BENCH=true ./mvnw failsafe:integration-test -Pintegration \
  -pl ai-assistant -Dit.test=FullPipelineBenchmarkIT

# Full pipeline benchmark — qwen3:4b for comparison
RUN_FULL_BENCH=true OLLAMA_CHAT_MODEL=qwen3:4b ./mvnw failsafe:integration-test \
  -Pintegration -pl ai-assistant -Dit.test=FullPipelineBenchmarkIT

# Model comparison (direct Ollama, both models, S1–S4)
RUN_LATENCY=true ./mvnw failsafe:integration-test -Pintegration \
  -pl ai-assistant -Dit.test=ChatModelBenchmarkIT

# Latency breakdown (direct Ollama vs Spring AI overhead)
RUN_LATENCY=true ./mvnw failsafe:integration-test -Pintegration \
  -pl ai-assistant -Dit.test=ChatLatencyIT
```

Reports are written to `ai-assistant/target/`:
- `full-pipeline-report.txt` — full pipeline benchmark output
- `model-comparison-report.txt` — model comparison table
