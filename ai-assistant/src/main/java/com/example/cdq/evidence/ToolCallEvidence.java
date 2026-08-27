package com.example.cdq.evidence;

public record ToolCallEvidence(
    String server,
    String tool,
    String argumentsJson,
    ToolInvocationStatus invocationStatus,
    ToolOutcome outcome,
    String errorCode
) {}
