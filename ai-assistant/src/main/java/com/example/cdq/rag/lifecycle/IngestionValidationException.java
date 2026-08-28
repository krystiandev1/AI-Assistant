package com.example.cdq.rag.lifecycle;

public class IngestionValidationException extends RuntimeException {
    public IngestionValidationException(String message) {
        super(message);
    }
}
