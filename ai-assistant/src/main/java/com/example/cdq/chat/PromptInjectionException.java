package com.example.cdq.chat;

public class PromptInjectionException extends RuntimeException {
    public PromptInjectionException() {
        super("Prompt injection pattern detected");
    }
}
