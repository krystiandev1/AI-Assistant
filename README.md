# CDQ AI Assistant

A Spring AI–based chat assistant for CDQ Fraud Guard knowledge, built as a recruitment task submission.

> **AI usage disclosure:** This project was built with the assistance of Claude Code (claude-sonnet-4-6). See [AI_USAGE.md](AI_USAGE.md) for a full explanation.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4.1.1, Spring AI 2.0.1 |
| Language | Java 21 |
| LLM | Ollama — `qwen3:4b` (task-compliant) / `qwen3:4b-instruct` (recommended) |
| Embeddings | Ollama — `qwen3-embedding:0.6b` (1024d, fixed) |
| Vector store | PostgreSQL + pgvector (HNSW, cosine, 1024d) |
| RAG pipeline | Spring AI `RetrievalAugmentationAdvisor` + custom `ActiveVersionDocumentRetriever` |
| MCP tools | Countries REST MCP server (port 8081) + Weather MCP (Node.js stdio) |

---

## Prerequisites

- Java 21+
- Maven 3.9+ (or use the included `mvnw`)
- Docker (for pgvector and Testcontainers integration tests)
- [Ollama](https://ollama.com) installed and running locally
- Node.js 18+ (required for the weather MCP server)
- API keys — see [API Keys](#api-keys) below

---

## Model Setup

Pull the required models before starting the application:

```bash
# Task-specified model — use this to match the recruitment requirement exactly
ollama pull qwen3:4b

# Recommended default — significantly faster for interactive chat
ollama pull qwen3:4b-instruct

# Embedding model — required, not configurable
ollama pull qwen3-embedding:0.6b
```

See [Chat Model Selection](#chat-model-selection) for the full rationale.

---

## API Keys

The application requires two free API keys:

| Service | Sign up | Purpose |
|---|---|---|
| REST Countries v5 | https://restcountries.com/sign-up | Country data (capital, region, population) |
| WeatherAPI.com | https://www.weatherapi.com/signup.aspx | Current weather by city |

Both are free tier with no credit card required. After signing up:

**Root `.env`** (read by the ai-assistant):

```bash
cp .env.example .env
# then edit .env and fill in your key
```

```
WEATHER_API_KEY=your_key_here
```

**`countries-mcp-server/.env`** (read by the Countries MCP server from its own working directory):

```
REST_COUNTRIES_API_KEY=your_key_here
REST_COUNTRIES_BASE_URL=https://api.restcountries.com
```

---

## Quick Start

Start services in this order. Each step depends on the previous one.

### 1. Start PostgreSQL with pgvector

```bash
docker compose up -d postgres
```

Or with a plain `docker run`:

```bash
docker run -d --name pgvector \
  -e POSTGRES_DB=cdq_assistant \
  -e POSTGRES_USER=cdq \
  -e POSTGRES_PASSWORD=cdq_secret \
  -p 5432:5432 \
  pgvector/pgvector:pg17
```

### 2. Build the weather MCP server

Required once after checkout (Node.js 18+ must be installed):

```bash
cd external/mcp-weather
npm install
npm run build
cd ../..
```

This produces `external/mcp-weather/dist/index.js`, which is launched as a subprocess by the ai-assistant at runtime.

### 3. Start the Countries MCP server

In a separate terminal, from the project root:

```bash
./mvnw spring-boot:run -pl countries-mcp-server
```

Wait for: `Started CountriesMcpApplication` (typically ~2 seconds).

**The ai-assistant will fail to start if this server is not running.**

### 4. Build and start the AI assistant

```bash
# Build once (or after code changes)
./mvnw -pl ai-assistant package -DskipTests

# Run from the project root — the weather MCP path is relative to this directory
java -jar ai-assistant/target/ai-assistant-0.1.0-SNAPSHOT.jar
```

> **Windows + SSL-intercepting antivirus (Avast, Kaspersky, ESET, Norton)?**
> These tools intercept HTTPS traffic and replace certificates with their own root CA. The JVM's
> built-in truststore doesn't know this CA, causing `PKIX path building failed` errors for country
> data. Fix: tell the JVM to use the Windows certificate store instead:
> ```bash
> java -Djavax.net.ssl.trustStoreType=Windows-ROOT -jar ai-assistant/target/ai-assistant-0.1.0-SNAPSHOT.jar
> ```
> The same flag applies when starting the countries MCP server:
> ```bash
> ./mvnw -pl countries-mcp-server spring-boot:run -Dspring-boot.run.jvmArguments="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
> ```

Wait for: `Started AssistantApplication`. The RAG ingestion runs automatically on first startup (~5 seconds).

The chat interface is available at **http://localhost:8080**.

---

## Chat Model Selection

### Task-compliant: `qwen3:4b`

```bash
# Linux / macOS
OLLAMA_CHAT_MODEL=qwen3:4b java -jar ai-assistant/target/ai-assistant-0.1.0-SNAPSHOT.jar

# Windows PowerShell
$env:OLLAMA_CHAT_MODEL = "qwen3:4b"
java -jar ai-assistant/target/ai-assistant-0.1.0-SNAPSHOT.jar
```

### Recommended default: `qwen3:4b-instruct`

No environment variable needed — this is the default. Benchmarks showed that `think=false` in the Ollama API does not suppress reasoning for `qwen3:4b` in the tested setup: reasoning routes to `message.content` instead of `message.thinking`, producing identical latency and token counts. The instruct variant resolves this:

| Scenario | `qwen3:4b` | `qwen3:4b-instruct` |
|---|---:|---:|
| Simple direct chat | ~9.9 s / 604 tok | ~0.16 s / 9 tok |
| CDQ question, no context | ~15.8 s / 1 004 tok | ~0.66 s / 37 tok |
| CDQ + simulated RAG | ~8.8 s / 550 tok | ~1.18 s / 67 tok |

Full rationale: [`docs/why-ollama-instant.md`](docs/why-ollama-instant.md).

---

## Sample Questions & Answers

The following responses were recorded from a live run with `qwen3:4b-instruct`.

### Q1 — What is the capital city of Germany?

> The capital city of Germany is Berlin.

**Routing:** `get_country("Germany")` → capital field from REST Countries API.

---

### Q2 — What is the temperature currently in Munich?

> The current temperature in Munich is 18.9°C.

**Routing:** `get_weather("Munich")` → WeatherAPI.com.

---

### Q3 — What is the temperature of the capital of Germany currently?

> The current temperature in the capital of Germany, Berlin, is 20.3°C.

**Routing:** `get_country("Germany")` → capital = "Berlin" → `get_weather("Berlin")`. Multi-tool chain: two MCP calls in sequence.

---

### Q4 — What do you know about Berlin?

> Berlin is the capital city of Germany. It is located in the central part of the country and is one of the most populous cities in Europe. Berlin is known for its rich history, cultural diversity, and vibrant arts and music scene. It is also a major center for technology, innovation, and research.

**Routing:** No tool calls, no RAG documents. The question is open-ended and non-time-sensitive — the system prompt allows model knowledge for broad city questions. This is intentional behaviour.

---

### Own questions

**"How does CDQ Fraud Guard reduce manual work?"**

> CDQ Fraud Guard reduces manual work by automating repetitive data-lookup and cross-referencing tasks for finance and risk teams. It leverages community-shared data and real-time fraud intelligence to eliminate the need for manual verification of bank accounts and transaction partners.

**Routing:** RAG — retrieved from local CDQ Fraud Guard knowledge base (no MCP tools).

**"What is the population of France, and what is the weather in its capital?"**

> France has a population of approximately 67,391,582. The current weather in Paris, the capital of France, is 22.1°C.

**Routing:** `get_country("France")` → population + capital = "Paris" → `get_weather("Paris")`.

---

## Running Tests

### Unit tests (fast, no external dependencies)

```bash
./mvnw test -pl ai-assistant
```

All 51 tests pass in ~5 seconds.

### Integration tests (requires Docker + Ollama)

```bash
./mvnw verify -Pintegration -pl ai-assistant
```

Testcontainers starts pgvector automatically. Ollama must be running. Total time ~2–5 minutes.

### Run a specific integration test class

```bash
./mvnw failsafe:integration-test -Pintegration -pl ai-assistant \
  -Dit.test=ChatRagIT
```

### Performance benchmarks (opt-in)

```bash
# Full pipeline benchmark
RUN_FULL_BENCH=true ./mvnw failsafe:integration-test -Pintegration \
  -pl ai-assistant -Dit.test=FullPipelineBenchmarkIT

# Model comparison (qwen3:4b vs qwen3:4b-instruct)
RUN_LATENCY=true ./mvnw failsafe:integration-test -Pintegration \
  -pl ai-assistant -Dit.test=ChatModelBenchmarkIT
```

Reports: `ai-assistant/target/full-pipeline-report.txt`, `model-comparison-report.txt`.
See [`docs/model-performance.md`](docs/model-performance.md).

---

## Project Structure

```
.
├── ai-assistant/               # Main Spring Boot application (port 8080)
│   └── src/
│       ├── main/java/com/example/cdq/
│       │   ├── chat/           # AssistantService, ChatController, ChatUiController
│       │   ├── config/         # ChatConfig, AppProperties
│       │   ├── evidence/       # EvidenceAccumulator, RagEvidence, ToolCallEvidence
│       │   └── rag/            # DocumentProcessor, RagRetrieval, lifecycle/
│       └── test/java/com/example/cdq/
│           └── chat/           # ChatRagIT, ChatVersionIT, benchmarks
├── countries-mcp-server/       # Spring AI MCP server — REST Countries v5 (port 8081)
├── external/
│   └── mcp-weather/            # Node.js MCP server — WeatherAPI.com (stdio)
├── docs/                       # Engineering decision records
├── docker-compose.yml
├── .env.example
└── AI_USAGE.md
```

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `OLLAMA_CHAT_MODEL` | `qwen3:4b-instruct` | Chat model name |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `POSTGRES_DB` | `cdq_assistant` | Database name |
| `POSTGRES_USER` | `cdq` | Database user |
| `POSTGRES_PASSWORD` | `cdq_secret` | Database password |
| `RAG_SIMILARITY_THRESHOLD` | `0.5` | Minimum cosine similarity for retrieval |
| `WEATHER_API_KEY` | _(empty)_ | WeatherAPI.com free tier key |

---

## Known Limitations

- **No conversation memory.** Each question is an independent request. Long-term and short-term memory are explicitly out of scope per the task definition.
- **CDQ content is a local file.** The RAG knowledge base (`rag/cdq-fraud-guard.md`) was manually scraped from `https://www.cdq.com/products/cdq-fraud-guard` and committed as a local Markdown file. The pipeline does not fetch from the live URL at runtime. This was a deliberate choice: reproducibility and offline operation take priority over live content staleness. The source URL is stored as provenance metadata on every embedded chunk.
- **Model default deviates from task.** The task specifies `qwen3:4b`; the default is `qwen3:4b-instruct`. The rationale is benchmarked and documented. The task-compliant model is fully supported via `OLLAMA_CHAT_MODEL=qwen3:4b`.
- **Weather MCP requires Node.js.** The weather MCP server is a Node.js/TypeScript process. Node.js 18+ must be installed and `npm run build` must be executed once before starting the application.
- **API keys required.** REST Countries v5 and WeatherAPI.com both require free registration. Without keys, country and weather tool calls return errors.
