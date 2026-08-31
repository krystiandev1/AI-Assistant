package com.example.cdq.config;

import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingProperties;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
class OllamaConfig {

    @Bean
    OllamaEmbeddingModel ollamaEmbeddingModel(OllamaApi ollamaApi, OllamaEmbeddingProperties props) {
        OllamaEmbeddingOptions options = OllamaEmbeddingOptions.builder()
            .model(props.getModel())
            .truncate(props.getTruncate())
            .keepAlive(props.getKeepAlive())
            .numCtx(2048)
            .build();
        return OllamaEmbeddingModel.builder()
            .ollamaApi(ollamaApi)
            .options(options)
            .modelManagementOptions(new ModelManagementOptions(
                PullModelStrategy.NEVER, List.of(), Duration.ofMinutes(5), 1))
            .build();
    }
}
