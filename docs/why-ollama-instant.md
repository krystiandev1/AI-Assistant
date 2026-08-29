# Why `qwen3:4b-instruct` Is the Default Interactive Model

_Engineering rationale for model selection in the CDQ AI Assistant_

---

## Requirement and Compatibility

The recruitment task specifies `qwen3:4b` as the local LLM via Ollama. This application fully supports that model — no code changes are required. The exact task model can be activated with a single environment variable:

```bash
# Linux / macOS
OLLAMA_CHAT_MODEL=qwen3:4b ./mvnw spring-boot:run -pl ai-assistant

# Windows PowerShell
$env:OLLAMA_CHAT_MODEL = "qwen3:4b"
.\mvnw.cmd spring-boot:run -pl ai-assistant
```

The default runtime is set to `qwen3:4b-instruct` based on measured latency in the interactive chat/RAG workload. This is an engineering decision — not a claim that `qwen3:4b-instruct` satisfies the exact tag requirement.

---

## The Core Problem: `think=false` Does Not Suppress Reasoning

The most direct path to resolving the latency issue would have been disabling the model's thinking mode. We verified that `think=false` was correctly sent as a top-level field in the Ollama `/api/chat` request body (not inside `options`), and measured its effect:

| Variant | Total | Output tokens | `message.thinking` | Reasoning in `message.content` |
|---|---:|---:|---|---|
| `think=true`, temp=0 | ~8.39 s | 550 | present | no |
| `think=false`, temp=0 | ~8.97 s | 550 | absent/empty | **yes — `<think>` leaks into content** |
| `think=true`, temp=0.6 top_p=0.95 top_k=20 | ~11.78 s | 710 | present | no |

**Key finding:** `think=false` changed the *routing* of the reasoning, not whether reasoning was generated:

```
think=true
→ reasoning in message.thinking
→ message.content contains only the final answer

think=false
→ reasoning is still generated
→ appears in message.content wrapped in <think> tags
→ message.thinking is absent
```

Output token count was identical (550 vs 550). Latency was essentially unchanged (8.39 s vs 8.97 s). The model was still performing the full reasoning pass — the parameter only changed which field the output appeared in.

---

## Sampling Parameters Were Not the Root Cause

The official Qwen3 documentation advises against greedy decoding (`temperature=0`) in thinking mode. We tested the recommended sampling configuration to rule this out as a confound:

```
temperature=0.6 / top_p=0.95 / top_k=20
```

Result in our workload:

| Sampling | Total | Output tokens |
|---|---:|---:|
| greedy (temp=0) with thinking | ~8.39 s | 550 |
| recommended Qwen3 sampling with thinking | ~11.78 s | 710 |

The recommended sampling parameters did not eliminate the problem — they increased output tokens and latency due to sampling variance. `temperature=0` was not the cause of the high latency; the model generates approximately the same volume of reasoning regardless of sampling strategy. We are not claiming greedy decoding is generally preferable — only that it did not explain the observed behaviour.

---

## Model Comparison

Direct Ollama measurements, WARM_RUNS=3, question: `"What is the Trust Score in CDQ Fraud Guard?"`

| Scenario | `qwen3:4b` | `qwen3:4b-instruct` | Improvement |
|---|---:|---:|---:|
| Simple 2+2 | ~9.86 s / 604 tok | ~0.16 s / 9 tok | much lower latency |
| CDQ question, no context | ~15.81 s / 1 004 tok | ~0.66 s / 37 tok | much lower latency |
| CDQ question + simulated RAG | ~8.79 s / 550 tok | ~1.18 s / 67 tok | ~7.4× lower latency |

**Why the difference:** Token throughput (tokens/second) was similar for both models (~60–65 tok/s). The entire latency gap comes from the number of output tokens generated. `qwen3:4b` generates long reasoning blocks; `qwen3:4b-instruct` produces a short direct answer.

In the tested workload, the instruct variant produced dramatically fewer output tokens and therefore much lower end-to-end latency. This is a workload-specific observation, not a general model ranking.

---

## Full Spring AI Application Path

The real application path for every chat request is:

```
AssistantService.ask()
  → ChatClient
  → RetrievalAugmentationAdvisor (with ContextualQueryAugmenter)
  → ActiveVersionDocumentRetriever
  → RagRetrieval → pgvector
  → OllamaChatModel
  → EvidenceAccumulator
  → ChatApiResponse
```

In the benchmark run (`ChatModelBenchmarkIT` S4), the full Spring AI path with `qwen3:4b-instruct` completed in approximately **~3.0 s** (single measured run). An earlier isolated `FullPipelineBenchmarkIT` run (no competing processes, 3 warm runs) produced a warm average of **~1.2 s**. Both are representative of the instruct model's real application latency.

### Spring AI is Not the Bottleneck

Instrumentation via `ChatModelSpy` (wrapping `OllamaChatModel`) showed:

- pgvector retrieval: ~58–103 ms
- Spring AI / application overhead (advisors + embedding + advisor pipeline): ~74–111 ms
- LLM generation: accounts for virtually all remaining time

The full advisor-generated prompt correlates with increased reasoning generation for `qwen3:4b`, but the investigation did not isolate one Spring AI component as the sole cause. The Spring AI infrastructure itself is not the bottleneck.

---

## Ollama Community Reports

The behaviour observed in this project — `think=false` failing to suppress reasoning for `qwen3:4b` — is consistent with multiple independent community reports:

- **[ollama/ollama#12022](https://github.com/ollama/ollama/issues/12022)** — *Qwen3:4B Performance Issue: think:false Parameter Not Working + Slower Than 8B*; reporter notes the issue appeared after a model update
- **[ollama/ollama#12234](https://github.com/ollama/ollama/issues/12234)** — *qwen3:4b still output thinking progress with Chat API think=false*
- **[ollama/ollama#13154](https://github.com/ollama/ollama/issues/13154)** — *think Parameter Not Suppressing Reasoning in qwen3:4b When Set to False*; includes comparison between raw Qwen3-4B and Ollama behaviour
- **[ollama/ollama#17588](https://github.com/ollama/ollama/issues/17588)** — *think:false does not consistently disable reasoning output for Qwen3 models through the Ollama API*; reproduced on `qwen3:4b` (2026)

These are community-filed issues, not official bug advisories. We do not present them as a formal guarantee that the issue is universally reproducible. They do provide independent confirmation that the behaviour is not unique to this project's setup.

---

## Official Qwen3 Documentation

The upstream model — [`Qwen/Qwen3-4B` on Hugging Face](https://huggingface.co/Qwen/Qwen3-4B) — is a hybrid model with documented `enable_thinking=True/False` support. The problem is therefore not an architectural limitation of the Qwen3-4B model itself. The behaviour was reproduced with the `qwen3:4b` Ollama configuration used in this project. Whether the issue lies in the Ollama GGUF conversion, the model file bundled under the `qwen3:4b` tag, or the API handling layer was not further investigated.

---

## Why the Task May Still Specify `qwen3:4b`

One possible explanation is that the recruitment task was prepared against an earlier Qwen3/Ollama combination where `think=false` worked as expected and produced non-thinking response latency.

This cannot be confirmed. Community reports indicate that the `think:false` behaviour regressed after a model or Ollama update, which would be consistent with this hypothesis — but we cannot determine when or whether that applies to the specific combination the task was designed against.

This is precisely why `qwen3:4b` remains fully supported and has not been removed. A recruiter can run the exact task model with a single environment variable and observe the application's full behaviour.

---

## Final Decision

### Default interactive model: `qwen3:4b-instruct`

Rationale:
- Interactive chat requires low end-to-end latency
- Measured benchmarks show dramatically fewer output tokens and much lower wall time
- RAG grounding works correctly for representative EN/PL queries
- Tool calling is functional
- Does not generate multi-second reasoning blocks on every request

### Strict task compatibility: `qwen3:4b`

```bash
OLLAMA_CHAT_MODEL=qwen3:4b
```

Rationale:
- The exact task requirement remains supported
- Verifiable without any code changes
- Transparency is preserved — the deviation is explicit and documented, not hidden

---

## Known Trade-offs

### German cross-lingual regression (instruct)

The retrieval pipeline correctly returns English knowledge base chunks for German-language queries (`qwen3-embedding:0.6b` handles cross-lingual semantic similarity). However, in one test case, `qwen3:4b-instruct` responded with `"Ich weiß nicht."` despite receiving 3 correct English sections in context.

This is a **generation / grounding issue, not a retrieval issue**:
- EN retrieval and grounding: working ✓
- PL cross-lingual retrieval and grounding: working ✓
- DE retrieval: working ✓ (correct chunks retrieved)
- DE grounding (instruct): partial — instruct model may decline to answer from English context when queried in German

This trade-off is documented and does not change the interactive default decision. It can be addressed through system prompt improvements (explicit multilingual grounding instructions) without changing the model.

`qwen3:4b` handles the same German test case correctly.

---

## Summary

```
Requirement:          qwen3:4b (recruitment task)
Status:               supported — OLLAMA_CHAT_MODEL=qwen3:4b

Interactive default:  qwen3:4b-instruct
Main reason:          measured token generation and end-to-end latency

Ollama issue:         think=false does not suppress reasoning in tested setup
                      reasoning routes to message.content instead of message.thinking
                      output token count unchanged

Community evidence:   4 independent reports (links above)

Fallback:             single environment variable, no code change required

Known trade-off:      DE cross-lingual generation regression (retrieval correct)
```

For full benchmark methodology and raw results, see [`docs/model-performance.md`](model-performance.md).
