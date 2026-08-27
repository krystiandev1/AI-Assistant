package com.example.cdq.evidence;

public record DecodedToolResult(ToolOutcome outcome, String errorCode) {

    public static DecodedToolResult unknown() {
        return new DecodedToolResult(ToolOutcome.UNKNOWN, null);
    }
}
