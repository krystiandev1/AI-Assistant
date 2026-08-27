package com.example.cdq.chat;

import com.example.cdq.evidence.ExecutionEvidence;

public record ChatApiResponse(String requestId, String answer, ExecutionEvidence evidence) {}
