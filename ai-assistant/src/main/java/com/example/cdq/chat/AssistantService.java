package com.example.cdq.chat;

import com.example.cdq.evidence.EvidenceAccumulator;
import com.example.cdq.evidence.EvidenceCapturingToolCallbackProvider;
import com.example.cdq.evidence.McpToolResultDecoder;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class AssistantService {

    private final ChatClient chatClient;
    private final McpToolResultDecoder toolResultDecoder;
    private final List<McpSyncClient> mcpClients;

    AssistantService(
            ChatClient chatClient,
            McpToolResultDecoder toolResultDecoder,
            ObjectProvider<List<McpSyncClient>> mcpClientsProvider) {
        this.chatClient = chatClient;
        this.toolResultDecoder = toolResultDecoder;
        this.mcpClients = mcpClientsProvider.stream().flatMap(List::stream).toList();
    }

    ChatApiResponse ask(ChatRequest request) {
        String requestId = UUID.randomUUID().toString();
        EvidenceAccumulator evidence = new EvidenceAccumulator(requestId);

        // Build per-server evidence-capturing wrappers for this invocation
        Object[] tools = mcpClients.stream()
            .map(client -> {
                String serverName = client.getClientInfo().title();
                SyncMcpToolCallbackProvider perServerProvider = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(client)
                    .build();
                return new EvidenceCapturingToolCallbackProvider(
                    perServerProvider, serverName, evidence, toolResultDecoder);
            })
            .toArray();

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
