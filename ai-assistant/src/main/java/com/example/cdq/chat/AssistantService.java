package com.example.cdq.chat;

import com.example.cdq.evidence.EvidenceAccumulator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class AssistantService {

    private final ChatClient chatClient;
    private final ToolCallbacksFactory toolCallbacksFactory;

    AssistantService(ChatClient chatClient, ToolCallbacksFactory toolCallbacksFactory) {
        this.chatClient = chatClient;
        this.toolCallbacksFactory = toolCallbacksFactory;
    }

    ChatApiResponse ask(ChatRequest request) {
        String requestId = UUID.randomUUID().toString();
        EvidenceAccumulator evidence = new EvidenceAccumulator(requestId);
        Object[] tools = toolCallbacksFactory.create(evidence);

        // qwen3:4b ignores system-prompt language rules when tool schemas add English context.
        // Prepending a language hint directly to the user message is the only reliable fix.
        String userMessage = withLanguageHint(request.question());

        ChatResponse response = chatClient.prompt()
            .user(userMessage)
            .tools(tools)
            .call()
            .chatResponse();

        // Extract RAG evidence from ChatResponse metadata
        if (response != null) {
            Object docsMeta = response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
            if (docsMeta instanceof List<?> docs) {
                docs.stream()
                    .filter(Document.class::isInstance)
                    .map(Document.class::cast)
                    .forEach(doc -> {
                        Map<String, Object> meta = doc.getMetadata();
                        evidence.recordRagDocument(
                            (String) meta.get("sourceId"),
                            (String) meta.get("sourceUrl"),
                            (String) meta.getOrDefault("section", "general"),
                            toInt(meta.getOrDefault("chunkIndex", 0)),
                            toLong(meta.get("sourceVersionId"))
                        );
                    });
            }
        }

        String answer = response != null && response.getResult() != null
            ? response.getResult().getOutput().getText()
            : "";

        return new ChatApiResponse(requestId, answer, evidence.build());
    }

    private static final String POLISH_CHARS = "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ";
    private static final String GERMAN_CHARS  = "äöüÄÖÜß";

    private static final java.util.regex.Pattern POLISH_WORDS = java.util.regex.Pattern.compile(
        "\\b(co|jak|jaka|jakie|jaki|czy|nie|jest|ile|gdzie|kiedy|dlaczego|wiesz|masz" +
        "|temperatura|stolica|aktualna|aktualny|powiedz|opisz|podaj|chce|moge|mozesz)\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    // Unambiguous German words that cannot appear in English text
    private static final java.util.regex.Pattern GERMAN_WORDS = java.util.regex.Pattern.compile(
        "\\b(ist|sind|haben|nicht|kannst|kannst|weißt|wissen|hauptstadt|deutschland|deutsch" +
        "|aktuell|temperatur|kannst|bitte|erkläre|beschreibe|welche|welcher|welches)\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    private static String withLanguageHint(String question) {
        if (question.chars().anyMatch(c -> POLISH_CHARS.indexOf(c) >= 0)
                || POLISH_WORDS.matcher(question).find()) {
            return "Odpowiedz po polsku (Polski). " + question;
        }
        if (question.chars().anyMatch(c -> GERMAN_CHARS.indexOf(c) >= 0)
                || GERMAN_WORDS.matcher(question).find()) {
            return "Antworte auf Deutsch. " + question;
        }
        return question;
    }

    private static int toInt(Object value) {
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        return 0;
    }

    private static long toLong(Object value) {
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }
}
