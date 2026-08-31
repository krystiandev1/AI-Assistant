package com.example.cdq.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(PromptInjectionException.class)
    ResponseEntity<Map<String, String>> handlePromptInjection() {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "Your request contains content that cannot be processed."));
    }
}
