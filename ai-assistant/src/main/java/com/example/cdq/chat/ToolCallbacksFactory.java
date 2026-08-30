package com.example.cdq.chat;

import com.example.cdq.evidence.EvidenceAccumulator;

@FunctionalInterface
public interface ToolCallbacksFactory {
    Object[] create(EvidenceAccumulator evidence);
}
