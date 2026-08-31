package com.example.cdq.chat;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared infrastructure for all chat integration tests.
 *
 * <p>Provides language-detection constants and assertion helpers used by
 * {@link ChatEndToEndIT} and {@link ChatMultiLanguageIT}, and Ollama availability
 * checks used by all three IT classes. Keeping these in one place ensures that a
 * change to the detection heuristics (e.g. adding a new language pattern) is
 * applied consistently across all test suites.
 */
abstract class AbstractChatIT {

    // ── Language characters and words ─────────────────────────────────────────

    private static final String POLISH_CHARS = "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ";
    private static final String GERMAN_CHARS  = "äöüÄÖÜß";

    private static final Pattern POLISH_WORDS = Pattern.compile(
        "\\b(jest|nie|tak|przez|oraz|który|która|które|tego|tej|ten|ta|to|się|jak|co)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern GERMAN_WORDS = Pattern.compile(
        "\\b(ist|sind|haben|nicht|die|der|das|ein|eine|und|von|für|mit|auf|als)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ENGLISH_WORDS = Pattern.compile(
        "\\b(is|are|the|of|in|and|to|a|an|it|this|that|for|with|has|have|be)\\b",
        Pattern.CASE_INSENSITIVE);

    // ── Language assertions ───────────────────────────────────────────────────

    protected static void assertPolish(String text) {
        boolean hasPolishDiacritics = text.chars().anyMatch(c -> POLISH_CHARS.indexOf(c) >= 0);
        boolean hasPolishWords = POLISH_WORDS.matcher(text).find();
        assertThat(hasPolishDiacritics || hasPolishWords)
            .as("Expected Polish response.\nActual: %s", text)
            .isTrue();
    }

    protected static void assertGerman(String text) {
        boolean hasGermanChars = text.chars().anyMatch(c -> GERMAN_CHARS.indexOf(c) >= 0);
        boolean hasGermanWords = GERMAN_WORDS.matcher(text).find();
        assertThat(hasGermanChars || hasGermanWords)
            .as("Expected German response.\nActual: %s", text)
            .isTrue();
    }

    protected static void assertEnglish(String text) {
        boolean noPolishChars = text.chars().noneMatch(c -> POLISH_CHARS.indexOf(c) >= 0);
        boolean noGermanChars = text.chars().noneMatch(c -> GERMAN_CHARS.indexOf(c) >= 0);
        boolean hasEnglishWords = ENGLISH_WORDS.matcher(text).find();
        assertThat(noPolishChars && noGermanChars && hasEnglishWords)
            .as("Expected English response.\nActual: %s", text)
            .isTrue();
    }

    // ── Routing assertions ────────────────────────────────────────────────────

    protected static void assertNoToolsOrRag(ChatApiResponse r) {
        assertThat(r.evidence().toolCalls()).as("No tool calls expected").isEmpty();
        assertThat(r.evidence().ragDocuments()).as("No RAG docs expected").isEmpty();
    }

    protected static void assertRagUsed(ChatApiResponse r) {
        assertThat(r.evidence().toolCalls()).as("No tool calls expected for RAG path").isEmpty();
        assertThat(r.evidence().ragDocuments()).as("RAG docs must be retrieved").isNotEmpty();
    }

    protected static void assertToolCalled(ChatApiResponse r, String toolName) {
        assertThat(r.evidence().toolCalls())
            .as("Expected tool '%s' to be called", toolName)
            .anyMatch(tc -> toolName.equals(tc.tool()));
    }

    protected static void assertToolArgContains(ChatApiResponse r, String toolName, String expected) {
        assertThat(r.evidence().toolCalls())
            .filteredOn(tc -> toolName.equals(tc.tool()))
            .as("Tool '%s' arg must contain '%s'", toolName, expected)
            .anyMatch(tc -> tc.argumentsJson() != null
                && tc.argumentsJson().toLowerCase().contains(expected.toLowerCase()));
    }

    // ── Ollama availability checks ────────────────────────────────────────────

    protected static boolean isOllamaRunning() {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URI("http://localhost:11434").toURL().openConnection();
            c.setConnectTimeout(2_000);
            c.connect();
            int status = c.getResponseCode();
            c.disconnect();
            return status >= 0;
        } catch (Exception e) { return false; }
    }

    protected static boolean isModelAvailable(String modelName) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URI("http://localhost:11434/api/tags").toURL().openConnection();
            c.setConnectTimeout(2_000);
            c.connect();
            try (var in = c.getInputStream()) {
                return new String(in.readAllBytes()).contains("\"" + modelName + "\"");
            } finally {
                c.disconnect();
            }
        } catch (Exception e) { return false; }
    }
}
