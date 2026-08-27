package com.example.cdq.evidence;

import java.util.List;

public record ExecutionEvidence(List<ToolCallEvidence> toolCalls, List<RagEvidence> ragDocuments) {}
