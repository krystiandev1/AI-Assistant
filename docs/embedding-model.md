# Embedding Model Decision

## English

### Why qwen3-embedding:0.6b

The project uses two separate Ollama models with distinct responsibilities:

- `qwen3:4b` — ChatModel: reasoning, response generation, MCP tool calling
- `qwen3-embedding:0.6b` — EmbeddingModel: text-to-vector conversion for RAG retrieval

**Key reasons for choosing qwen3-embedding:0.6b over nomic-embed-text:**

- **Better retrieval quality** — MTEB score 64.33 vs 62.39; meaningful at scale (1 000+ documents)
- **Cross-lingual retrieval** — English knowledge base, Polish/German user queries work without translation; this is the primary differentiator
- **L2-normalized output** — confirmed in Qwen3 documentation; enables correct cosine similarity in pgvector
- **32K context window** — supports longer document chunks without truncation risk
- **Active development** — Qwen3 family (2025); nomic-embed-text is a mature but older generation model

**Conscious trade-off:** requires pgvector dimensions: 1024 instead of 768. This is a one-line config change accepted intentionally in Phase 1 before the schema is locked.

### Single source of configuration

The embedding dimension is defined once in `application.yml`:

```yaml
app:
  rag:
    embedding-expected-dimensions: 1024
```

This value is read by tests and will be used to initialize the pgvector schema in Phase 2. Changing the embedding model requires updating this value — the dimension test will immediately signal the mismatch.

### Future trade-offs

- Switching embedding models requires **full reindexing** of all pgvector documents
- 1024 dimensions produce a slightly larger HNSW index than 768 dimensions — negligible for < 100 000 documents
- If the knowledge base becomes exclusively English and multilingual support is no longer needed, `nomic-embed-text` (274 MB vs 639 MB) is a lighter alternative

---

## Polski

### Dlaczego qwen3-embedding:0.6b

Projekt używa dwóch oddzielnych modeli Ollama z jasno rozdzielonymi odpowiedzialnościami:

- `qwen3:4b` — ChatModel: rozumowanie, generowanie odpowiedzi, wywoływanie narzędzi MCP
- `qwen3-embedding:0.6b` — EmbeddingModel: zamiana tekstu na wektory dla RAG retrieval

**Kluczowe powody wyboru qwen3-embedding:0.6b zamiast nomic-embed-text:**

- **Lepsza jakość retrieval** — MTEB score 64.33 vs 62.39; różnica odczuwalna przy dużym zbiorze dokumentów
- **Cross-lingual retrieval** — angielski knowledge base, zapytania użytkowników po polsku/niemiecku działają bez tłumaczenia; to główna przewaga tego modelu
- **L2-normalized output** — potwierdzone w dokumentacji Qwen3; poprawna podobieństwo cosinusowe w pgvector
- **32K context window** — obsługuje dłuższe fragmenty dokumentów bez ryzyka obcięcia
- **Aktywny development** — rodzina Qwen3 (2025); nomic-embed-text to dojrzała, ale starsza generacja

**Świadomy trade-off:** wymaga pgvector dimensions: 1024 zamiast 768. Jest to zmiana jednej liczby w konfiguracji, zaakceptowana świadomie w Fazie 1, zanim schemat zostanie zamrożony.

### Jedno źródło konfiguracji

Wymiar embeddingu jest zdefiniowany raz w `application.yml`:

```yaml
app:
  rag:
    embedding-expected-dimensions: 1024
```

Ta wartość jest odczytywana przez testy i zostanie użyta do inicjalizacji schematu pgvector w Fazie 2. Zmiana modelu embeddingowego wymaga aktualizacji tej wartości — test wymiaru natychmiast sygnalizuje niezgodność.

### Przyszłe trade-offy

- Zmiana modelu embeddingowego wymaga **pełnego reindeksowania** wszystkich dokumentów w pgvector
- Wymiar 1024 tworzy nieco większy indeks HNSW niż 768 — nieistotne dla < 100 000 dokumentów
- Jeśli knowledge base stanie się wyłącznie angielski i wielojęzyczność przestanie być potrzebna, `nomic-embed-text` (274 MB vs 639 MB) jest lżejszą alternatywą

---

## Test documentation

`CosineSimilarityTest` — unit tests, no Spring, no Ollama required:

| Test | Purpose |
|---|---|
| `identical_vectors_have_similarity_one` | Verifies math: same direction = similarity 1.0 |
| `orthogonal_vectors_have_zero_similarity` | Verifies math: perpendicular vectors = similarity 0.0 |
| `antiparallel_vectors_have_negative_similarity` | Verifies math: opposite direction = similarity -1.0 |
| `mismatched_lengths_throw_exception` | Guards against silent bugs when vector dimensions differ |
| `zero_vector_returns_zero` | Edge case: avoids divide-by-zero |

`EmbeddingModelIT` — integration tests, require `ollama serve` + `ollama pull qwen3-embedding:0.6b`:

| Test | Purpose |
|---|---|
| `generates_non_null_non_empty_embedding` | Smoke test: Ollama is reachable, Spring AI deserializes correctly |
| `embedding_has_expected_dimension` | pgvector contract: dimension must equal `app.rag.embedding-expected-dimensions`; fails fast on model swap |
| `all_values_are_finite` | Guards against NaN/Infinity corrupting distance calculations silently |
| `embedding_is_l2_normalized` | Confirms L2 normalization (documented for qwen3-embedding); required for correct cosine similarity |
| `semantically_similar_sentences_are_closer_than_unrelated` | Functional contract: model preserves English semantic relationships |
| `cross_lingual_queries_are_closer_to_english_document_than_unrelated_text` | Validates the model's cross-lingual capability (PL + DE vs EN); fails if replaced by a monolingual model |
| `same_input_produces_stable_embedding` | Determinism: identical input must yield identical vector on repeated calls |
