package com.example.cdq.config;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * Appends a hard {@code TARGET_OUTPUT_LANGUAGE} instruction to every user message, after
 * the RAG advisor has already completed its embedding search. This preserves two invariants:
 * <ul>
 *   <li>RAG retrieval uses the original clean question (no embedding pollution).
 *   <li>The language instruction is compact enough to not confuse qwen3:4b tool routing.
 * </ul>
 *
 * <p>The target language is pre-detected by {@link com.example.cdq.chat.InputLanguageDetector}
 * using Lingua and passed via {@code AdvisorSpec.param("targetLanguage", value)} on each request.
 * Defaults to {@code "English"} if no language was specified.
 *
 * <p>Order is between RAG ({@code DEFAULT_ORDER - 50}) and {@code ToolCallingAdvisor}
 * ({@code DEFAULT_ORDER}), so the hint is invisible to pgvector but visible to qwen3:4b.
 */
public class LanguageHintAdvisor implements BaseAdvisor {

    public static final String TARGET_LANGUAGE_PARAM = "targetLanguage";

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String language = (String) request.context().getOrDefault(TARGET_LANGUAGE_PARAM, "English");
        String hint = buildHint(language);
        // Append AFTER RAG has already run (this advisor is at DEFAULT_ORDER - 25,
        // RAG runs at DEFAULT_ORDER - 50). Appending exploits recency bias and
        // avoids polluting the RAG embedding query.
        var augmented = request.prompt()
            .augmentUserMessage(um -> um.mutate().text(um.getText() + hint).build());
        return request.mutate().prompt(augmented).build();
    }

    // Bilingual hint: native-language instruction alongside English reinforces output language
    // for qwen3:4b which has strong English bias on general-knowledge topics.
    private static String buildHint(String language) {
        String instruction = switch (language) {
            case "German"  -> "Respond in German / Antworte auf Deutsch.";
            case "Polish"  -> "Respond in Polish / Odpowiedz po polsku.";
            case "English" -> "Respond in English.";
            default        -> throw new IllegalArgumentException(
                "Unsupported language '%s' — add it to InputLanguageDetector and LanguageHintAdvisor".formatted(language));
        };
        return "\n[" + instruction + " Use English for tool arguments.]";
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public int getOrder() {
        return ToolCallingAdvisor.DEFAULT_ORDER - 25;
    }

}
