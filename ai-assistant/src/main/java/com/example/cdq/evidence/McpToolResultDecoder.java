package com.example.cdq.evidence;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Parses the MCP content-array envelope returned by ToolCallback.call().
 *
 * MCP results arrive as: [{"type":"text","text":"{\"status\":\"OK\",...}"}]
 * This decoder finds the first text content item and parses the inner JSON
 * for status/errorCode to populate evidence without touching the actual result.
 *
 * Phase 0I: envelope format verified before this decoder was frozen.
 */
@Component
public final class McpToolResultDecoder {

    private final ObjectMapper objectMapper;

    public McpToolResultDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DecodedToolResult decode(String rawResult) {
        try {
            JsonNode root = objectMapper.readTree(rawResult);
            if (!root.isArray()) {
                return DecodedToolResult.unknown();
            }

            for (JsonNode content : root) {
                if (!"text".equals(content.path("type").asString())) {
                    continue;
                }
                String text = content.path("text").asString(null);
                if (text == null) {
                    continue;
                }
                JsonNode payload = objectMapper.readTree(text);
                String status = payload.path("status").asString();
                if ("OK".equals(status)) {
                    return new DecodedToolResult(ToolOutcome.OK, null);
                }
                if ("ERROR".equals(status)) {
                    String errorCode = payload.path("errorCode").asString(null);
                    return new DecodedToolResult(ToolOutcome.ERROR, errorCode);
                }
            }
        } catch (Exception ignored) {
        }
        return DecodedToolResult.unknown();
    }
}
