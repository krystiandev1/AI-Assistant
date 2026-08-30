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

        ChatResponse response = chatClient.prompt()
            .user(request.question())
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
