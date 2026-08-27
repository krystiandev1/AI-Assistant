package com.example.cdq.evidence;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;

public final class EvidenceCapturingToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;
    private final String serverName;
    private final EvidenceAccumulator accumulator;
    private final McpToolResultDecoder toolResultDecoder;

    public EvidenceCapturingToolCallbackProvider(ToolCallbackProvider delegate, String serverName,
            EvidenceAccumulator accumulator, McpToolResultDecoder toolResultDecoder) {
        this.delegate = delegate;
        this.serverName = serverName;
        this.accumulator = accumulator;
        this.toolResultDecoder = toolResultDecoder;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return Arrays.stream(delegate.getToolCallbacks())
            .map(cb -> new EvidenceCapturingToolCallback(cb, serverName, accumulator, toolResultDecoder))
            .toArray(ToolCallback[]::new);
    }
}
