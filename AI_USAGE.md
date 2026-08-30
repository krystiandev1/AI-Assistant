# AI Usage — CDQ AI Assistant

_This document fulfils the recruitment task requirement: "Using AI is explicitly allowed; explain how you used AI to fulfill the task."_

---

## English

### Tool used

[Claude Code](https://claude.ai/code) — Anthropic's AI coding assistant (model: `claude-sonnet-4-6`) — was used as the primary AI tool throughout the project. All sessions were interactive: the developer directed the work, reviewed every change, and made all architectural and product decisions.

---

### How AI was used

#### Architecture and design

The overall system design — RAG pipeline, MCP tool chaining, evidence collection, document lifecycle — was designed in collaboration with Claude Code. The developer defined the requirements and constraints; the AI proposed implementation approaches and trade-offs. All final decisions (technology choices, module boundaries, data model) were made by the developer.

#### Code generation

The majority of the Java source code was written with AI assistance:

- `AssistantService` — chat orchestration, per-request evidence accumulation, tool callback wrapping
- `DocumentLifecycleService` — idempotent ingestion state machine (hash + fingerprint versioning)
- `EvidenceCapturingToolCallback` / `EvidenceAccumulator` — transparent tool call interception for evidence collection
- `RagRetrieval` / `ActiveVersionDocumentRetriever` — version-filtered vector similarity search
- `RestCountriesClient` — WebClient-based REST Countries v5 integration
- `ChatController`, `ChatApiResponse` — REST API layer
- `ChatUiController` + `index.html` — Thymeleaf chat interface

In each case, the developer reviewed and tested the generated code, requested corrections, and made targeted edits.

#### Testing

Test strategy and test cases were designed by the developer. Claude Code wrote the test implementations:

- `RagPipelineIT` — 19 integration tests covering retrieval quality (Hit@1, Hit@3), cross-lingual queries, negative retrieval
- `DocumentLifecycleIT` — 16 tests for the version state machine
- `ChatRagIT` — 15 end-to-end RAG quality tests
- `RestCountriesClientTest` — WireMock-based unit tests
- Unit tests for `CosineSimilarity`, `EvidenceAccumulator`, `McpToolResultDecoder`, `DocumentProcessor`

The developer ran all tests, diagnosed failures, and directed fixes.

#### Weather MCP server patches

The upstream `semdin/mcp-weather` TypeScript server was forked from GitHub. Three patches were applied with AI assistance and documented in `external/mcp-weather/PATCH.md`:

1. **Patch 1** — Removed `dotenv` dependency (Spring AI injects env vars into stdio child processes; file-based dotenv caused path issues on Windows)
2. **Patch 2** — Improved tool description for reliable LLM tool selection
3. **Patch 3** — Structured JSON responses compatible with `McpToolResultDecoder`

#### Debugging and troubleshooting

Claude Code helped diagnose several runtime issues:

- `WebClient.Builder` not auto-configured in Spring Boot 4.x with mixed servlet/reactive classpath → added explicit `@Bean`
- SSL certificate validation failure for `api.restcountries.com` → configured Netty with permissive trust manager for development
- Weather MCP `dist/index.js` path resolved relative to Maven module directory, not project root → switched to running the ai-assistant as a jar from the project root
- Port 8081 zombie process on Windows → diagnosed via PowerShell `Get-NetTCPConnection`

#### Documentation

The following documentation was written with AI assistance and reviewed by the developer:

- `README.md` — tech stack, quick start, model rationale, sample Q&A, known limitations
- `docs/why-ollama-instant.md` — benchmark methodology and `think=false` analysis
- `docs/model-performance.md` — performance investigation
- `PATCH.md` — weather MCP fork change log
- `AI_USAGE.md` — this document

---

### What was done without AI

- Defining the project scope, requirements interpretation, and all product decisions
- Choosing to use `qwen3:4b-instruct` as the default after running benchmarks and analysing the results personally
- Obtaining and managing API keys (REST Countries, WeatherAPI.com)
- Setting up the local development environment (Ollama, Docker, Node.js)
- Final review and approval of every file before commit

---

### Honest assessment

AI assistance significantly accelerated the implementation — particularly for boilerplate-heavy areas (Spring AI configuration, Testcontainers setup, WebClient integration). The more domain-specific decisions (RAG similarity threshold tuning, evidence model design, system prompt engineering) required more developer involvement and iteration. The AI occasionally suggested over-engineered solutions or made incorrect assumptions about API contracts, which the developer caught during review.

---

---

## Polski

### Narzędzie

[Claude Code](https://claude.ai/code) — asystent kodowania od Anthropic (model: `claude-sonnet-4-6`) — był głównym narzędziem AI używanym przez cały projekt. Wszystkie sesje były interaktywne: developer kierował pracą, przeglądał każdą zmianę i podejmował wszystkie decyzje architektoniczne i produktowe.

---

### Jak AI było używane

#### Architektura i projekt systemu

Ogólny projekt systemu — pipeline RAG, łańcuchowanie narzędzi MCP, zbieranie dowodów (evidence), lifecycle dokumentów — był projektowany we współpracy z Claude Code. Developer definiował wymagania i ograniczenia; AI proponowało podejścia implementacyjne i kompromisy. Wszystkie ostateczne decyzje (wybór technologii, granice modułów, model danych) były podejmowane przez developera.

#### Generowanie kodu

Większość kodu Java została napisana z pomocą AI:

- `AssistantService` — orkiestracja chatu, akumulacja dowodów per-request, owijanie callbacków narzędzi
- `DocumentLifecycleService` — maszyna stanów idempotentnej ingestii (wersjonowanie przez hash + fingerprint)
- `EvidenceCapturingToolCallback` / `EvidenceAccumulator` — przechwytywanie wywołań narzędzi dla evidence collection
- `RagRetrieval` / `ActiveVersionDocumentRetriever` — wyszukiwanie wektorowe filtrowane po aktywnej wersji
- `RestCountriesClient` — integracja z REST Countries v5 przez WebClient
- `ChatController`, `ChatApiResponse` — warstwa REST API
- `ChatUiController` + `index.html` — interfejs czatu w Thymeleaf

W każdym przypadku developer przeglądał i testował wygenerowany kod, zgłaszał poprawki i wprowadzał celowane zmiany.

#### Testy

Strategia testów i przypadki testowe były projektowane przez developera. Claude Code pisał implementacje testów:

- `RagPipelineIT` — 19 testów integracyjnych pokrywających jakość retrievalu (Hit@1, Hit@3), zapytania cross-lingual, negatywny retrieval
- `DocumentLifecycleIT` — 16 testów maszyny stanów wersjonowania
- `ChatRagIT` — 15 end-to-end testów jakości RAG
- `RestCountriesClientTest` — testy jednostkowe z WireMock
- Testy jednostkowe dla `CosineSimilarity`, `EvidenceAccumulator`, `McpToolResultDecoder`, `DocumentProcessor`

Developer uruchamiał wszystkie testy, diagnozował błędy i kierował poprawkami.

#### Patche serwera pogodowego

Upstream serwer TypeScript `semdin/mcp-weather` został sforkowany z GitHub. Trzy patche zostały zastosowane z pomocą AI i udokumentowane w `external/mcp-weather/PATCH.md`:

1. **Patch 1** — usunięcie zależności `dotenv` (Spring AI wstrzykuje zmienne środowiskowe do procesów stdio; dotenv oparty na pliku powodował problemy ze ścieżką na Windows)
2. **Patch 2** — ulepszone opisy narzędzi dla niezawodnego wyboru przez LLM
3. **Patch 3** — ustrukturyzowane odpowiedzi JSON kompatybilne z `McpToolResultDecoder`

#### Debugowanie i rozwiązywanie problemów

Claude Code pomógł zdiagnozować kilka problemów uruchomieniowych:

- `WebClient.Builder` nie auto-konfigurowany w Spring Boot 4.x z mieszanym classpath servlet/reactive → dodano jawny `@Bean`
- Błąd walidacji certyfikatu SSL dla `api.restcountries.com` → skonfigurowano Netty z permissive trust manager dla środowiska developerskiego
- Ścieżka `dist/index.js` weather MCP rozwiązywana względem katalogu modułu Maven, nie rootu projektu → zmieniono uruchamianie ai-assistant jako jar z rootu projektu
- Zombie proces na porcie 8081 (Windows) → zdiagnozowany przez PowerShell `Get-NetTCPConnection`

#### Dokumentacja

Następująca dokumentacja została napisana z pomocą AI i przejrzana przez developera:

- `README.md` — stack technologiczny, quick start, uzasadnienie modelu, przykładowe Q&A, znane ograniczenia
- `docs/why-ollama-instant.md` — metodologia benchmarków i analiza `think=false`
- `docs/model-performance.md` — analiza wydajności
- `PATCH.md` — log zmian forka weather MCP
- `AI_USAGE.md` — ten dokument

---

### Co zostało zrobione bez AI

- Definiowanie zakresu projektu, interpretacja wymagań i wszystkie decyzje produktowe
- Wybór `qwen3:4b-instruct` jako domyślnego modelu po osobistym uruchomieniu benchmarków i analizie wyników
- Pozyskanie i zarządzanie kluczami API (REST Countries, WeatherAPI.com)
- Konfiguracja lokalnego środowiska developerskiego (Ollama, Docker, Node.js)
- Finalna weryfikacja i akceptacja każdego pliku przed commitem

---

### Uczciwa ocena

Pomoc AI znacznie przyspieszyła implementację — szczególnie w obszarach z dużą ilością boilerplate (konfiguracja Spring AI, setup Testcontainers, integracja WebClient). Bardziej domenowo-specyficzne decyzje (dobieranie progu podobieństwa RAG, projekt modelu evidence, inżynieria system promptu) wymagały większego zaangażowania developera i więcej iteracji. AI czasami proponowało zbyt skomplikowane rozwiązania lub robiło błędne założenia co do kontraktów API, co developer wychwytywał podczas przeglądu.
