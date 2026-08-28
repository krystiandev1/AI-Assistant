# Stage 3: RAG Document Lifecycle

## English

### Overview

Stage 3 adds a production-grade document lifecycle on top of the Stage 2 ingestion pipeline. The core guarantee: an ingestion failure — whether during embedding, validation, or activation — never corrupts the currently serving ACTIVE version. Users continue to receive answers from the last known-good version while the new one fails safely in the background.

```
application startup
  └─► RagStartupSynchronizer.run()          [ConditionalOnProperty: startup-sync-enabled]
        └─► DocumentLifecycleService.synchronize(sourceKey)
              │
              ├─[1] read resource → normalize (CRLF→LF, stripTrailing) → one canonical String
              ├─[2] sourceHash     = SHA-256(canonicalContent)
              ├─[3] fingerprint    = SHA-256(sourceHash|model|dims|processorVersion)
              ├─[4] ACTIVE version with same fingerprint?  → YES: return (zero Ollama calls)
              ├─[TX] createProcessingVersion()  → new row in rag_source_version (status=PROCESSING)
              ├─[I/O] documentProcessor.process(ByteArrayResource, versionId, ...)
              ├─[I/O] vectorStore.add(chunks)   ← Ollama embeddings
              ├─[validate] COUNT(chunks) == COUNT(stored)?  → NO: throw → markFailed(REQUIRES_NEW)
              └─[TX] activateVersion()          → ACTIVE→RETIRED + new→ACTIVE + pointer update
                      └─ any failure → markFailed(REQUIRES_NEW); old ACTIVE intact ✓
```

---

### Data model

Two tables manage the lifecycle state:

```sql
-- One row per source document (e.g., "cdq-fraud-guard")
CREATE TABLE rag_source (
    id                BIGSERIAL     PRIMARY KEY,
    source_key        VARCHAR(255)  NOT NULL UNIQUE,
    source_url        VARCHAR(1024) NOT NULL,
    active_version_id BIGINT        -- nullable; no FK constraint (Stage 4 via Flyway)
);

-- Full version history per source
CREATE TABLE rag_source_version (
    id                   BIGSERIAL    PRIMARY KEY,
    source_id            BIGINT       NOT NULL REFERENCES rag_source(id),
    source_hash          CHAR(64)     NOT NULL,  -- SHA-256 of canonical content
    pipeline_fingerprint CHAR(64)     NOT NULL,  -- SHA-256(sourceHash|model|dims|version)
    status               VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING'
                             CHECK (status IN ('PROCESSING','ACTIVE','RETIRED','FAILED')),
    embedding_model      VARCHAR(255) NOT NULL,
    embedding_dimensions INTEGER      NOT NULL,
    processor_version    VARCHAR(50)  NOT NULL,
    chunk_count          INTEGER,                -- null during PROCESSING
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activated_at         TIMESTAMPTZ,
    failure_reason       TEXT
);

-- DB-level guarantee: at most one ACTIVE version per source
CREATE UNIQUE INDEX uidx_rag_source_version_active
    ON rag_source_version (source_id)
    WHERE status = 'ACTIVE';
```

**State machine:**

```
PROCESSING ──► ACTIVE ──► RETIRED
     └───────► FAILED
```

RETIRED versions are kept in the database because their embedding vectors in pgvector are reused during rollback — without re-embedding.

---

### Hash and fingerprint strategy

Canonical content is read and normalized **exactly once** in `DocumentLifecycleService.synchronize()`. The same normalized bytes are:

1. Hashed (SHA-256) to produce `sourceHash`
2. Wrapped in a `ByteArrayResource` and passed to `DocumentProcessor`

This guarantees that the hash and the embedded content are always identical.

```
normalize(rawContent):
  raw.replace("\r\n", "\n").replace("\r", "\n").stripTrailing()

sourceHash = SHA-256(canonicalContent)

pipelineFingerprint = SHA-256(
  sourceHash + "|" + embeddingModel + "|" + embeddingDimensions + "|" + processorVersion
)
```

**Skip condition:** if an ACTIVE version with the same `pipelineFingerprint` already exists, `synchronize()` returns immediately — no Ollama calls, no DB writes. This makes restarts free when the document has not changed.

---

### Transaction boundaries

The orchestrator (`DocumentLifecycleService`) is **not** `@Transactional`. Long Ollama I/O must run outside any transaction to avoid holding DB connections for seconds.

Transactional DB operations are isolated in a separate `@Component` called `RagVersionTransactions`. This is the solution to the **Spring AOP self-invocation problem**: if `@Transactional` methods were on the same class as `synchronize()`, calling `this.markFailed()` internally would bypass the proxy and ignore the annotation.

| Method | Propagation | Purpose |
|---|---|---|
| `createProcessingVersion()` | REQUIRED | Insert PROCESSING row + upsert rag_source |
| `activateVersion()` | REQUIRED | RETIRE old + ACTIVATE new + update pointer (3 UPDATEs atomically) |
| `markFailed()` | **REQUIRES_NEW** | Persist FAILED even if outer code is rolling back |
| `rollbackTo()` | REQUIRED | RETIRE current + ACTIVATE target + update pointer |

`markFailed()` with `REQUIRES_NEW` is the safety net: even if `activateVersion()` or the embedding step throws and the outer "transaction" (conceptual, not DB) is unwinding, `markFailed()` opens a new, independent transaction that commits successfully. The old ACTIVE version is never touched.

---

### Lifecycle metadata per chunk

`DocumentProcessor.process()` now embeds five lifecycle fields into every chunk's metadata:

| Field | Type | Value |
|---|---|---|
| `sourceVersionId` | Long | FK to `rag_source_version.id` (used as filter key) |
| `sourceHash` | String (64 hex) | SHA-256 of the source document |
| `pipelineFingerprint` | String (64 hex) | SHA-256 of the full pipeline configuration |
| `chunkHash` | String (64 hex) | SHA-256 of this specific chunk's text |
| `chunkIndex` | Integer | Sequential index within the version |

---

### Retrieval filtering: RagRetrieval

`RagRetrieval.search()` restricts vector similarity search to chunks belonging to the current ACTIVE version:

```java
Long activeVersionId = sourceRepository.findActiveVersionId(sourceKey);
var filter = new FilterExpressionBuilder()
    .eq("sourceVersionId", activeVersionId)   // typed Long — Spring AI 2.0.1
    .build();
return vectorStore.similaritySearch(
    SearchRequest.builder()
        .query(query).topK(topK).similarityThreshold(threshold)
        .filterExpression(filter)
        .build()
);
```

RETIRED chunk vectors remain in pgvector but are invisible to search. This is intentional: they are available for rollback without re-embedding.

---

### Rollback

`DocumentLifecycleService.rollbackTo(targetVersionId)` restores a RETIRED version to ACTIVE:

- **Zero Ollama calls** — the embedding vectors already exist in pgvector
- **No-op guard** — if the target version is already ACTIVE, returns immediately
- **State guard** — PROCESSING or FAILED versions cannot be activated; throws `IllegalStateException`
- Flow: RETIRE current ACTIVE → ACTIVATE target → update `rag_source.active_version_id`

---

### Startup synchronization

`RagStartupSynchronizer` is an `ApplicationRunner` controlled by a feature flag:

```yaml
app:
  rag:
    startup-sync-enabled: true   # application.yml — enabled in production
    startup-sync-enabled: false  # test profiles — tests call synchronize() manually
```

The `@ConditionalOnProperty(matchIfMissing = true)` default means the bean is active unless explicitly disabled. In integration tests, each test profile sets `false` so that no synchronization happens before the test's `@BeforeAll` runs.

Startup failures are caught and logged — they do not crash the application. Retrieval returns empty results until the source is successfully ingested.

---

### Test strategy

**Unit tests** (no Spring context, no Docker):
- `DocumentHasherTest` (7): normalization, SHA-256 hex length=64, determinism, CRLF==LF, trailing whitespace
- `PipelineFingerprintTest` (5): each component (sourceHash, model, dims, processorVersion) when changed produces a different fingerprint
- `DocumentProcessorTest` (21): all existing Stage 2 assertions + 5 new lifecycle metadata assertions (sourceVersionId, sourceHash, pipelineFingerprint, chunkHash uniqueness, determinism of chunkHash)

**Integration tests** (Testcontainers pgvector + Ollama):

`DocumentLifecycleIT` (16 tests, profile `lifecycle-it`):

| Group | Tests |
|---|---|
| Idempotency (3) | first sync creates ACTIVE; second sync → no new version; second sync → no new vectors |
| Change detection (5) | new content → new version; different sourceHash; old → RETIRED; new → ACTIVE; pointer updated |
| Metadata (5) | all chunks have sourceVersionId, sourceHash (64-hex), pipelineFingerprint, chunkHash; hashes are unique per chunk |
| Rollback guards (3) | rollback to ACTIVE = no-op; rollback to RETIRED = makes it ACTIVE; rollback to FAILED = throws |

`RagPipelineIT` (20 tests, 2 `@Disabled`):
- Migrated from `RagIngestionService.ingest()` to `DocumentLifecycleService.synchronize()`
- Migrated from direct `VectorStore.similaritySearch()` to `RagRetrieval.search()` (which filters by active version)
- All 18 active tests pass with the lifecycle API

---

### Trade-offs

| Decision | Choice | Reason |
|---|---|---|
| Schema migrations | `schema.sql` with `IF NOT EXISTS` | Recruitment demo; production would use Flyway |
| FK `active_version_id` | No constraint in Stage 3 | PostgreSQL 17 lacks `ADD CONSTRAINT IF NOT EXISTS`; enforced at application level; Flyway Stage 4 |
| RETIRED chunk cleanup | Not implemented | RETIRED chunks are required for rollback without re-embedding; cleanup is Stage 4 |
| `@Transactional` isolation | Separate `RagVersionTransactions` bean | Spring AOP proxy only applies on calls through the proxy; self-invocation bypasses it |
| Concurrency | Described, not implemented | Single instance; unique partial index prevents two concurrent ACTIVEs at DB level; Stage 4: SELECT FOR UPDATE |
| `DocumentProcessor.id` | `UUID.randomUUID()` | Pipeline fingerprint prevents duplicate ingestion; deterministic IDs are unnecessary |

---

---

## Polski

### Przegląd

Stage 3 dodaje produkcyjny lifecycle dokumentów na szczycie pipeline'u ingestionu z Stage 2. Kluczowa gwarancja: niepowodzenie ingestionu — zarówno podczas embeddingu, walidacji, jak i aktywacji — nigdy nie niszczy aktualnie serwowanej wersji ACTIVE. Użytkownicy nadal otrzymują odpowiedzi z ostatniej prawidłowej wersji, podczas gdy nowa bezpiecznie zapisywana jest jako FAILED.

```
start aplikacji
  └─► RagStartupSynchronizer.run()          [ConditionalOnProperty: startup-sync-enabled]
        └─► DocumentLifecycleService.synchronize(sourceKey)
              │
              ├─[1] odczytaj zasób → normalizuj (CRLF→LF, stripTrailing) → jeden canonical String
              ├─[2] sourceHash     = SHA-256(canonicalContent)
              ├─[3] fingerprint    = SHA-256(sourceHash|model|dims|processorVersion)
              ├─[4] wersja ACTIVE z tym fingerprint?  → TAK: return (zero wywołań Ollama)
              ├─[TX] createProcessingVersion()  → nowy wiersz rag_source_version (status=PROCESSING)
              ├─[I/O] documentProcessor.process(ByteArrayResource, versionId, ...)
              ├─[I/O] vectorStore.add(chunks)   ← embeddingi Ollama
              ├─[walidacja] COUNT(chunks) == COUNT(stored)?  → NIE: throw → markFailed(REQUIRES_NEW)
              └─[TX] activateVersion()          → ACTIVE→RETIRED + nowa→ACTIVE + aktualizacja wskaźnika
                      └─ każdy błąd → markFailed(REQUIRES_NEW); stara ACTIVE nienaruszona ✓
```

---

### Model danych

Dwie tabele zarządzają stanem lifecycle:

```sql
-- Jeden wiersz per dokument źródłowy (np. "cdq-fraud-guard")
CREATE TABLE rag_source (
    id                BIGSERIAL     PRIMARY KEY,
    source_key        VARCHAR(255)  NOT NULL UNIQUE,
    source_url        VARCHAR(1024) NOT NULL,
    active_version_id BIGINT        -- nullable; bez FK constraint (Stage 4 przez Flyway)
);

-- Pełna historia wersji per dokument
CREATE TABLE rag_source_version (
    id                   BIGSERIAL    PRIMARY KEY,
    source_id            BIGINT       NOT NULL REFERENCES rag_source(id),
    source_hash          CHAR(64)     NOT NULL,  -- SHA-256 canonical content
    pipeline_fingerprint CHAR(64)     NOT NULL,  -- SHA-256(sourceHash|model|dims|version)
    status               VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING'
                             CHECK (status IN ('PROCESSING','ACTIVE','RETIRED','FAILED')),
    embedding_model      VARCHAR(255) NOT NULL,
    embedding_dimensions INTEGER      NOT NULL,
    processor_version    VARCHAR(50)  NOT NULL,
    chunk_count          INTEGER,                -- null podczas PROCESSING
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activated_at         TIMESTAMPTZ,
    failure_reason       TEXT
);

-- Gwarancja na poziomie DB: max jedna wersja ACTIVE per source
CREATE UNIQUE INDEX uidx_rag_source_version_active
    ON rag_source_version (source_id)
    WHERE status = 'ACTIVE';
```

**Maszyna stanów:**

```
PROCESSING ──► ACTIVE ──► RETIRED
     └───────► FAILED
```

Wersje RETIRED pozostają w bazie, ponieważ ich wektory embeddingów w pgvector są ponownie wykorzystywane podczas rollbacku — bez konieczności ponownego wywołania Ollamy.

---

### Strategia hashowania i fingerprinting

Canonical content jest odczytywany i normalizowany **dokładnie raz** w `DocumentLifecycleService.synchronize()`. Te same znormalizowane bajty są:

1. Hashowane (SHA-256) → `sourceHash`
2. Opakowane w `ByteArrayResource` i przekazane do `DocumentProcessor`

Gwarantuje to, że hash i osadzona treść są zawsze identyczne.

```
normalize(rawContent):
  raw.replace("\r\n", "\n").replace("\r", "\n").stripTrailing()

sourceHash = SHA-256(canonicalContent)

pipelineFingerprint = SHA-256(
  sourceHash + "|" + embeddingModel + "|" + embeddingDimensions + "|" + processorVersion
)
```

**Warunek pominięcia:** jeśli wersja ACTIVE z tym samym `pipelineFingerprint` już istnieje, `synchronize()` kończy działanie natychmiast — bez wywołań Ollamy, bez zapisów do bazy. Restart aplikacji bez zmian dokumentu jest bezkosztowy.

---

### Granice transakcji

Orkiestrator (`DocumentLifecycleService`) **nie jest** `@Transactional`. Długie I/O do Ollamy musi działać poza transakcją, żeby nie trzymać połączeń DB przez sekundy.

Operacje DB z transakcjami są izolowane w osobnym `@Component` o nazwie `RagVersionTransactions`. Jest to rozwiązanie **problemu self-invocation w Spring AOP**: gdyby metody `@Transactional` były na tej samej klasie co `synchronize()`, wywołanie `this.markFailed()` wewnętrznie ominęłoby proxy i zignorowało adnotację.

| Metoda | Propagacja | Cel |
|---|---|---|
| `createProcessingVersion()` | REQUIRED | INSERT wiersza PROCESSING + upsert rag_source |
| `activateVersion()` | REQUIRED | RETIRE stara + ACTIVATE nowa + aktualizacja wskaźnika (3 UPDATEs atomowo) |
| `markFailed()` | **REQUIRES_NEW** | Zapis FAILED nawet gdy outer kod się wycofuje |
| `rollbackTo()` | REQUIRED | RETIRE aktualna + ACTIVATE target + aktualizacja wskaźnika |

`markFailed()` z `REQUIRES_NEW` to siatka bezpieczeństwa: nawet jeśli `activateVersion()` wyrzuca wyjątek i zewnętrzny kod się odwija, `markFailed()` otwiera nową, niezależną transakcję, która kończy się commit. Stara wersja ACTIVE nigdy nie jest dotykana.

---

### Metadane lifecycle per chunk

`DocumentProcessor.process()` osadza teraz pięć pól lifecycle w metadanych każdego chunk:

| Pole | Typ | Wartość |
|---|---|---|
| `sourceVersionId` | Long | FK do `rag_source_version.id` (klucz filtra retrieval) |
| `sourceHash` | String (64 hex) | SHA-256 dokumentu źródłowego |
| `pipelineFingerprint` | String (64 hex) | SHA-256 pełnej konfiguracji pipeline |
| `chunkHash` | String (64 hex) | SHA-256 tekstu tego konkretnego chunka |
| `chunkIndex` | Integer | Kolejny indeks w ramach wersji |

---

### Filtrowanie retrieval: RagRetrieval

`RagRetrieval.search()` ogranicza wyszukiwanie podobieństwa wektorowego do chunków należących do aktualnej wersji ACTIVE:

```java
Long activeVersionId = sourceRepository.findActiveVersionId(sourceKey);
var filter = new FilterExpressionBuilder()
    .eq("sourceVersionId", activeVersionId)   // typed Long — Spring AI 2.0.1
    .build();
return vectorStore.similaritySearch(
    SearchRequest.builder()
        .query(query).topK(topK).similarityThreshold(threshold)
        .filterExpression(filter)
        .build()
);
```

Wektory chunków RETIRED pozostają w pgvector, ale są niewidoczne dla wyszukiwania. Jest to celowe: są dostępne do rollbacku bez ponownego generowania embeddingów.

---

### Rollback

`DocumentLifecycleService.rollbackTo(targetVersionId)` przywraca wersję RETIRED do stanu ACTIVE:

- **Zero wywołań Ollamy** — wektory embeddingów już istnieją w pgvector
- **Guard no-op** — jeśli target jest już ACTIVE, zwraca natychmiast
- **Guard stanu** — wersje PROCESSING lub FAILED nie mogą być aktywowane; rzuca `IllegalStateException`
- Flow: RETIRE aktualna ACTIVE → ACTIVATE target → aktualizacja `rag_source.active_version_id`

---

### Synchronizacja przy starcie

`RagStartupSynchronizer` to `ApplicationRunner` kontrolowany przez flagę:

```yaml
app:
  rag:
    startup-sync-enabled: true   # application.yml — włączone produkcyjnie
    startup-sync-enabled: false  # profile testowe — testy wywołują synchronize() ręcznie
```

`@ConditionalOnProperty(matchIfMissing = true)` oznacza, że bean jest aktywny domyślnie, chyba że jawnie wyłączony. W testach integracyjnych każdy profil ustawia `false`, aby synchronizacja nie następowała przed `@BeforeAll`.

Błędy przy starcie są przechwytywane i logowane — nie crashują aplikacji. Retrieval zwraca puste wyniki do momentu pomyślnego ingestionu.

---

### Strategia testów

**Testy jednostkowe** (bez Spring context, bez Dockera):
- `DocumentHasherTest` (7): normalizacja, SHA-256 hex długość=64, determinizm, CRLF==LF, trailing whitespace
- `PipelineFingerprintTest` (5): każdy komponent (sourceHash, model, dims, processorVersion) po zmianie daje inny fingerprint
- `DocumentProcessorTest` (21): wszystkie asercje Stage 2 + 5 nowych asercji metadanych lifecycle (sourceVersionId, sourceHash, pipelineFingerprint, chunkHash unikalność, determinizm chunkHash)

**Testy integracyjne** (Testcontainers pgvector + Ollama):

`DocumentLifecycleIT` (16 testów, profil `lifecycle-it`):

| Grupa | Testy |
|---|---|
| Idempotency (3) | pierwszy sync tworzy ACTIVE; drugi sync → brak nowej wersji; drugi sync → brak nowych wektorów |
| Change detection (5) | nowa treść → nowa wersja; inny sourceHash; stara → RETIRED; nowa → ACTIVE; wskaźnik zaktualizowany |
| Metadata (5) | wszystkie chunki mają sourceVersionId, sourceHash (64-hex), pipelineFingerprint, chunkHash; hashe unikalne per chunk |
| Rollback guards (3) | rollback do ACTIVE = no-op; rollback do RETIRED = aktywuje; rollback do FAILED = rzuca wyjątek |

`RagPipelineIT` (20 testów, 2 `@Disabled`):
- Zmigrowany z `RagIngestionService.ingest()` na `DocumentLifecycleService.synchronize()`
- Zmigrowany z bezpośredniego `VectorStore.similaritySearch()` na `RagRetrieval.search()` (filtruje po aktywnej wersji)
- Wszystkie 18 aktywnych testów przechodzi z nowym API lifecycle

---

### Trade-offy

| Decyzja | Wybór | Powód |
|---|---|---|
| Migracje schematu | `schema.sql` z `IF NOT EXISTS` | Demo rekrutacyjne; produkcja = Flyway |
| FK `active_version_id` | Brak constraintu w Stage 3 | PostgreSQL 17 nie obsługuje `ADD CONSTRAINT IF NOT EXISTS`; wymuszane na poziomie aplikacji; Flyway Stage 4 |
| Czyszczenie chunków RETIRED | Niezaimplementowane | Wektory RETIRED są wymagane do rollbacku bez re-embeddingu; cleanup Stage 4 |
| Izolacja `@Transactional` | Osobny bean `RagVersionTransactions` | Spring AOP proxy działa tylko przez proxy; self-invocation je omija |
| Współbieżność | Opisana, niezaimplementowana | Jedna instancja; unique partial index zapobiega dwóm ACTIVE per source na poziomie DB; Stage 4: SELECT FOR UPDATE |
| `DocumentProcessor.id` chunka | `UUID.randomUUID()` | Fingerprint pipeline zapobiega duplikatom; deterministyczne ID są zbędne |
