package com.example.cdq.config;

import org.springframework.ai.chat.client.ChatClient;
import com.example.cdq.rag.ActiveVersionDocumentRetriever;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
class ChatConfig {

    // Lower order = higher precedence = executes BEFORE ToolCallingAdvisor.
    // Retrieval runs once for the original user question, not once per tool iteration.
    private static final int RAG_ADVISOR_ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 50;

    @Value("classpath:prompts/system-prompt.st")
    private Resource systemPromptResource;

    @Bean
    ChatClient chatClient(ChatModel chatModel, ActiveVersionDocumentRetriever documentRetriever) throws IOException {
        String systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);

        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
            .documentRetriever(documentRetriever)
            .queryAugmenter(ContextualQueryAugmenter.builder()
                .allowEmptyContext(true)
                .build())
            .order(RAG_ADVISOR_ORDER)
            .build();

        return ChatClient.builder(chatModel)
            .defaultSystem(systemPrompt)
            .defaultAdvisors(ragAdvisor, new LanguageHintAdvisor())
            .build();
    }
}
