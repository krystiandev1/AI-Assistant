# CDQ AI Assistant

A Spring AI–based chat assistant for CDQ Fraud Guard knowledge, built as a recruitment task submission.

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4.1.1, Spring AI 2.0.1 |
| Language | Java 21 |
| LLM | Ollama — `qwen3:4b-instruct` (default) / `qwen3:4b` |
| Embeddings | Ollama — `qwen3-embedding:0.6b` (1024d, fixed) |
| Vector store | PostgreSQL + pgvector (HNSW, cosine, 1024d) |
| RAG pipeline | Spring AI `RetrievalAugmentationAdvisor` + custom `ActiveVersionDocumentRetriever` |
| MCP tools | Countries REST MCP server (port 8081) + Weather MCP (Node.js stdio) |

---

## Prerequisites

- Java 21+
- Maven 3.9+ (or use the included `mvnw`)
- Docker (Testcontainers uses it for integration tests)
- [Ollama](https://ollama.com) installed and running locally
- PostgreSQL with pgvector extension (or Docker — see below)

---

## Model Setup

Pull both models before starting the application:

```bash
# Recommended default — fast interactive chat
ollama pull qwen3:4b-instruct

# Exact model specified in the recruitment task — compatibility/strict-task option
ollama pull qwen3:4b

# Embedding model — required, unchanged
ollama pull qwen3-embedding:0.6b
```

See [Chat Model Selection](#chat-model-selection) below for why two models are listed and which one to use.

---

## Quick Start

### 1. Start PostgreSQL with pgvector

```bash
docker run -d --name pgvector \
  -e POSTGRES_DB=cdq_assistant \
  -e POSTGRES_USER=cdq \
  -e POSTGRES_PASSWORD=cdq_secret \
  -p 5432:5432 \
  pgvector/pgvector:pg17
```

### 2. Start the application (recommended — instruct model default)

```bash
./mvnw spring-boot:run -pl ai-assistant
```

This uses `qwen3:4b-instruct` by default. No environment variable needed.

### 3. Start with the strict task model

**Linux / macOS / bash:**
```bash
OLLAMA_CHAT_MODEL=qwen3:4b ./mvnw spring-boot:run -pl ai-assistant
```

**Windows PowerShell:**
```powershell
$env:OLLAMA_CHAT_MODEL = "qwen3:4b"
.\mvnw.cmd spring-boot:run -pl ai-assistant
```

The application is available at `http://localhost:8080`.

---

## Why `qwen3:4b-instruct` Is the Default

The recruitment task specifies `qwen3:4b`. This application fully supports that model — it can be activated without any code changes (see below). The default runtime uses `qwen3:4b-instruct` because benchmarks showed that `think=false` in the Ollama API does not suppress reasoning for `qwen3:4b` in the tested setup: output token count and latency are unchanged, and reasoning routes to `message.content` instead of `message.thinking`. The instruct variant produces dramatically fewer output tokens and correspondingly lower latency for the same workload.

| Scenario | `qwen3:4b` | `qwen3:4b-instruct` |
|---|---:|---:|
| Simple direct chat | ~9.9 s / 604 tok | ~0.16 s / 9 tok |
| CDQ question, no context | ~15.8 s / 1 004 tok | ~0.66 s / 37 tok |
| CDQ + simulated RAG | ~8.8 s / 550 tok | ~1.18 s / 67 tok |
| EN/PL grounding | working | working |
| DE cross-lingual grounding | working | partial — instruct may decline |
| Tool calling | supported | supported |

_Results are environment-specific. Measured via direct Ollama HTTP, WARM_RUNS=3._

To run the exact task model:

```bash
# Linux / macOS
OLLAMA_CHAT_MODEL=qwen3:4b ./mvnw spring-boot:run -pl ai-assistant

# Windows PowerShell
$env:OLLAMA_CHAT_MODEL = "qwen3:4b"
.\mvnw.cmd spring-boot:run -pl ai-assistant
```

Full engineering rationale, benchmark methodology, `think=false` analysis, community reports, and known trade-offs: [`docs/why-ollama-instant.md`](docs/why-ollama-instant.md).

### Model Configuration

The model is configured in `ai-assistant/src/main/resources/application.yml`:

```yaml
spring:
  ai:
    ollama:
      chat:
        model: ${OLLAMA_CHAT_MODEL:qwen3:4b-instruct}
```

The embedding model is fixed and not configurable at runtime:

```yaml
      embedding:
        model: qwen3-embedding:0.6b
```

---

## Running Tests

### Unit tests only (fast, no external dependencies)

```bash
./mvnw test -pl ai-assistant
```

All tests should pass in ~5 seconds.

### Integration tests (requires Docker + Ollama)

```bash
./mvnw verify -Pintegration -pl ai-assistant
```

Integration tests use Testcontainers (pgvector auto-started via Docker). Ollama must be running. All tests should pass; total time is ~2–5 minutes depending on model load time.

### Run a specific integration test class

```bash
./mvnw failsafe:integration-test -Pintegration -pl ai-assistant \
  -Dit.test=ChatRagIT
```

### Performance benchmarks (manual, opt-in)

Benchmark tests are guarded by environment variables and are excluded from normal `mvn verify` runs:

```bash
# Full pipeline benchmark (requires Ollama, Docker)
RUN_FULL_BENCH=true ./mvnw failsafe:integration-test -Pintegration \
  -pl ai-assistant -Dit.test=FullPipelineBenchmarkIT

# Run with qwen3:4b for comparison
RUN_FULL_BENCH=true OLLAMA_CHAT_MODEL=qwen3:4b ./mvnw failsafe:integration-test \
  -Pintegration -pl ai-assistant -Dit.test=FullPipelineBenchmarkIT

# Model comparison (direct Ollama, both models)
RUN_LATENCY=true ./mvnw failsafe:integration-test -Pintegration \
  -pl ai-assistant -Dit.test=ChatModelBenchmarkIT

# Latency breakdown (direct Ollama vs Spring AI overhead)
RUN_LATENCY=true ./mvnw failsafe:integration-test -Pintegration \
  -pl ai-assistant -Dit.test=ChatLatencyIT
```

Reports are written to `ai-assistant/target/`:
- `full-pipeline-report.txt`
- `model-comparison-report.txt`

See [`docs/model-performance.md`](docs/model-performance.md) for the full investigation and result interpretation.

---

## Project Structure

```
demo1/
├── ai-assistant/          # Main Spring Boot application (port 8080)
│   └── src/
│       ├── main/
│       │   └── java/com/example/cdq/
│       │       ├── chat/               # AssistantService, ChatController
│       │       ├── config/             # ChatConfig, AppProperties
│       │       ├── evidence/           # RagEvidence, EvidenceAccumulator
│       │       ├── rag/                # DocumentProcessor, RagRetrieval
│       │       │   └── lifecycle/      # DocumentLifecycleService, version state machine
│       │       └── embedding/          # CosineSimilarity utilities
│       └── test/
│           └── java/com/example/cdq/
│               └── chat/
│                   ├── ChatRagIT.java              # E2E RAG quality tests (15)
│                   ├── ChatVersionIT.java          # Active-version switching tests (3)
│                   ├── FullPipelineBenchmarkIT.java # Performance benchmark (guard: RUN_FULL_BENCH)
│                   ├── ChatModelBenchmarkIT.java   # Model comparison (guard: RUN_LATENCY)
│                   └── ChatLatencyIT.java          # Latency breakdown (guard: RUN_LATENCY)
├── countries-mcp-server/  # Spring AI MCP server for REST Countries API (port 8081)
├── external/
│   └── mcp-weather/       # Node.js MCP server for weather (stdio)
└── docs/
    ├── model-performance.md    # Full performance investigation
    ├── embedding-model.md
    ├── rag-ingestion-pipeline.md
    └── rag-document-lifecycle.md
```

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `OLLAMA_CHAT_MODEL` | `qwen3:4b-instruct` | Chat LLM model name |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `POSTGRES_DB` | `cdq_assistant` | Database name |
| `POSTGRES_USER` | `cdq` | Database user |
| `POSTGRES_PASSWORD` | `cdq_secret` | Database password |
| `RAG_SIMILARITY_THRESHOLD` | `0.5` | Minimum cosine similarity for retrieval |
| `WEATHER_API_KEY` | _(empty)_ | WeatherAPI.com key for weather MCP |
| `REST_COUNTRIES_API_KEY` | _(empty)_ | REST Countries API key |
