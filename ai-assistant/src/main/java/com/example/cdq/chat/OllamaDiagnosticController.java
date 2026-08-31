package com.example.cdq.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/debug")
class OllamaDiagnosticController {

    private final RestClient restClient;

    OllamaDiagnosticController(@Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(ollamaBaseUrl).build();
    }

    @GetMapping("/ollama-ps")
    ResponseEntity<String> ollamaPs() {
        String response = restClient.get()
            .uri("/api/ps")
            .retrieve()
            .body(String.class);
        return ResponseEntity.ok(response);
    }
}
