package com.example.cdq.evidence;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static com.example.cdq.evidence.ToolInvocationStatus.COMPLETED;
import static com.example.cdq.evidence.ToolInvocationStatus.FAILED;
import static com.example.cdq.evidence.ToolOutcome.UNKNOWN;

public final class EvidenceCapturingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCapturingToolCallback.class);

    // Synthetic error in MCP content-array format — returned to LLM when Stage 1 fails
    private static final String TECHNICAL_ERROR_RESULT =
        "[{\"type\":\"text\",\"text\":\"{\\\"status\\\":\\\"ERROR\\\",\\\"errorCode\\\":\\\"MCP_INVOCATION_FAILED\\\"}\"}]";

    private final ToolCallback delegate;
    private final String serverName;
    private final EvidenceAccumulator accumulator;
    private final McpToolResultDecoder toolResultDecoder;

    public EvidenceCapturingToolCallback(ToolCallback delegate, String serverName,
            EvidenceAccumulator accumulator, McpToolResultDecoder toolResultDecoder) {
        this.delegate = delegate;
        this.serverName = serverName;
        this.accumulator = accumulator;
        this.toolResultDecoder = toolResultDecoder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        String result;
        // Stage 1: actual MCP invocation — exception here means FAILED/UNKNOWN
        try {
            result = delegate.call(toolInput);
        } catch (Exception ex) {
            accumulator.recordToolCall(serverName, delegate.getToolDefinition().name(), toolInput,
                FAILED, UNKNOWN, "MCP_INVOCATION_FAILED");
            return TECHNICAL_ERROR_RESULT;
        }

        // Stage 2: evidence inspection — exception here NEVER changes the tool result
        ToolOutcome outcome = UNKNOWN;
        String errorCode = null;
        try {
            DecodedToolResult decoded = toolResultDecoder.decode(result);
            outcome = decoded.outcome();
            errorCode = decoded.errorCode();
        } catch (Exception ex) {
            log.warn("Could not decode tool result for evidence. tool={}",
                delegate.getToolDefinition().name(), ex);
        }

        accumulator.recordToolCall(serverName, delegate.getToolDefinition().name(), toolInput,
            COMPLETED, outcome, errorCode);
        return result;
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return call(toolInput);
    }
}
