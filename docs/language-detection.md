# Language Detection Design

The assistant mirrors the user's input language in every response — model-knowledge answers, RAG
answers, and tool-call answers — across English, German, and Polish.

---

## Motivation

`qwen3:4b` has a strong English bias. Without an explicit language constraint, it reverts to English
after receiving tool results (which are always in English JSON), even when the original question was
in Polish or German. A system-prompt language rule is insufficient because the model overrides it
once it processes English tool content. The fix must be applied **per-request**, after tool schemas
are already in context.

---

## Detection Pipeline (`InputLanguageDetector`)

Detection runs in four priority levels. A higher-priority rule short-circuits all lower ones.

```
Input text
    │
    ▼ Priority 1 — character-level markers (always unambiguous)
    ├─ Contains Polish diacritics? [ą ć ę ł ń ó ś ź ż ...] → "Polish"
    ├─ Contains German umlauts?  [ä ö ü Ä Ö Ü ß]           → "German"
    │
    ▼ Priority 2 — Lingua statistical model (fromAllSpokenLanguages, ~75 languages)
    ├─ POLISH  → "Polish"
    ├─ GERMAN  → "German"
    ├─ ENGLISH → "English"
    ├─ UNKNOWN → continue to Priority 3 (text too short/ambiguous)
    └─ anything else (ICELANDIC, FRENCH, CZECH …) → "Unsupported"
    │
    ▼ Priority 3 — function word fallback (for UNKNOWN only)
    ├─ German-only words: ist / sind / nicht → "German"
    ├─ Polish-only words: co / czy / jak / gdzie / kiedy … → "Polish"
    └─ (none matched) → "English"
```

### Why `fromAllSpokenLanguages()` instead of `fromLanguages(EN, DE, PL)`

A narrow 3-language Lingua detector is forced to return the nearest match from only three options.
Icelandic gets classified as English; Czech gets classified as Polish. The wide model knows
~75 languages and returns `UNKNOWN` only when confidence is genuinely too low — never a
forced nearest-match. This makes the `"Unsupported"` path reliable.

### Why character markers before Lingua

Character-level detection is O(n) with no model I/O and is infallible for its languages. Running
Lingua first would waste compute on text where the answer is already known.

### Why function words after Lingua

Lingua's minimum confidence threshold causes it to return `UNKNOWN` for very short queries
(typically under 5–7 words). The function word fallback recovers these cases:

| Query | Reason Lingua fails | Rescued by |
|---|---|---|
| `"co to cdq?"` | 3 tokens, no diacritics | `co` in POLISH_WORDS |
| `"Was ist das?"` | 3 tokens | `ist` in GERMAN_WORDS |

Function words are chosen to have zero English overlap. Words with English homographs
(`"to"`, `"ten"`, `"jest"`, `"ta"`) are deliberately excluded.

---

## Language Hint Advisor (`LanguageHintAdvisor`)

After language detection, `AssistantService` passes the result via Spring AI's advisor
parameter mechanism:

```java
chatClient.prompt()
    .user(question)
    .advisors(spec -> spec.param(TARGET_LANGUAGE_PARAM, targetLanguage))
    ...
```

`LanguageHintAdvisor` implements `BaseAdvisor` and runs at
`ToolCallingAdvisor.DEFAULT_ORDER - 25`, which places it **after** the RAG advisor
(`DEFAULT_ORDER - 50`) and **before** `ToolCallingAdvisor` (`DEFAULT_ORDER`).

This ordering preserves two invariants:

1. **RAG embedding is clean.** The language hint is appended *after* the retrieval advisor has
   already executed its vector search. The embedding query uses the original user text, not the
   augmented version.

2. **The hint is visible to the model.** The augmented message is in context when the model
   reasons about tool calls and composes its final answer.

The hint itself is bilingual — it includes both the native language and English — because
`qwen3:4b` has an English bias that a purely native-language instruction cannot fully overcome:

```
[Respond in Polish / Odpowiedz po polsku. Use English for tool arguments.]
```

The "Use English for tool arguments" clause prevents the model from passing city names in the
user's language (e.g. `"Monachium"` instead of `"Munich"`) to tools that expect English input.

---

## Unsupported Language Handling

When `InputLanguageDetector.detect()` returns `"Unsupported"`, `AssistantService` short-circuits
**before** the LLM call and returns a fixed trilingual error message:

```
I'm sorry, I can only answer questions in English, German, or Polish.
Leider kann ich nur auf Englisch, Deutsch oder Polnisch antworten.
Przepraszam, mogę odpowiadać tylko po angielsku, niemiecku lub polsku.
```

The message is trilingual so that a supported-language user who accidentally switches keyboard
layout or input language still receives a readable response. The LLM is never called, which means
no tokens are spent and no tool calls are attempted (avoiding misrouted tool arguments like
`get_weather("Varsjá")` for Icelandic input).

---

## Test Coverage

| Class | Type | What it verifies |
|---|---|---|
| `InputLanguageDetectorTest` | Unit | Detection logic for all supported + unsupported languages; null/blank guard |
| `ChatMultiLanguageIT` | Integration | Language mirroring across model-knowledge, RAG, and fake-tool paths |
| `ChatEndToEndIT` | Integration (E2E) | Language mirroring with **real** Ollama + real MCP tools (15 tests) |
| `ChatToolRoutingIT` | Integration | Tool routing correctness (not language — uses fake tools) |

### `AbstractChatIT` — shared test base

All three IT classes extend `AbstractChatIT`, which centralises:

- Language assertion helpers (`assertPolish`, `assertGerman`, `assertEnglish`) — each uses
  character-set membership and compiled `Pattern` word matching
- Routing assertion helpers (`assertToolCalled`, `assertRagUsed`, `assertNoToolsOrRag`)
- Ollama availability checks (`isOllamaRunning`, `isModelAvailable`) with proper connection cleanup

### `ChatEndToEndIT` prerequisites

```bash
# Ollama with required models
ollama serve
ollama pull qwen3:4b
ollama pull qwen3-embedding:0.6b

# Countries MCP server
./mvnw spring-boot:run -pl countries-mcp-server

# Weather API key in .env
WEATHER_API_KEY=your_key

# Run
./mvnw verify -Pintegration -pl ai-assistant -Dit.test=ChatEndToEndIT
```

---

## Known Limitations

| Limitation | Impact | Status |
|---|---|---|
| `qwen3:4b` KV cache contamination | Routing tests become flaky after 6+ similar queries in one JVM session | Mitigated by soft routing assertions; document explains the cause |
| German function word false-positives | `"What ist the capital?"` (typo) misdetected as German | Acceptable for the demo; would require context-aware correction |
| Short Polish queries without diacritics | Only partially covered by function words; `"cdq"` alone → English | Lingua minimum confidence threshold; add more Polish words if needed |
| Lingua model load time | `fromAllSpokenLanguages()` loads ~75 language models at Spring context startup | One-time cost per JVM; acceptable for a server application |
