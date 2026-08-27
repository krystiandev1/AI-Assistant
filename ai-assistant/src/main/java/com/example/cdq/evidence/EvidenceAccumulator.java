package com.example.cdq.evidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-invocation accumulator. Plain class — NOT a Spring bean.
 * Created fresh in AssistantService.ask() for each user request.
 */
public final class EvidenceAccumulator {

    private final String requestId;
    private final List<ToolCallEvidence> toolCalls = new ArrayList<>();
    private final List<RagEvidence> ragDocuments = new ArrayList<>();

    public EvidenceAccumulator(String requestId) {
        this.requestId = requestId;
    }

    public void recordToolCall(
        String server,
        String tool,
        String argumentsJson,
        ToolInvocationStatus invocationStatus,
        ToolOutcome outcome,
        String errorCode
    ) {
        toolCalls.add(new ToolCallEvidence(server, tool, argumentsJson, invocationStatus, outcome, errorCode));
    }

    public void recordRagDocument(String sourceId, String sourceUrl, String section, int chunkIndex) {
        ragDocuments.add(new RagEvidence(sourceId, sourceUrl, section, chunkIndex));
    }

    public String requestId() {
        return requestId;
    }

    public ExecutionEvidence build() {
        return new ExecutionEvidence(
            Collections.unmodifiableList(toolCalls),
            Collections.unmodifiableList(ragDocuments)
        );
    }
}
