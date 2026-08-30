package com.example.cdq.config;

import com.example.cdq.chat.ToolCallbacksFactory;
import com.example.cdq.evidence.EvidenceCapturingToolCallbackProvider;
import com.example.cdq.evidence.McpToolResultDecoder;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
class McpToolCallbacksConfig {

    @Bean
    @ConditionalOnMissingBean
    ToolCallbacksFactory toolCallbacksFactory(
            ObjectProvider<List<McpSyncClient>> mcpClientsProvider,
            McpToolResultDecoder decoder) {

        List<McpSyncClient> clients = mcpClientsProvider.stream().flatMap(List::stream).toList();

        return evidence -> clients.stream()
            .map(client -> (Object) new EvidenceCapturingToolCallbackProvider(
                SyncMcpToolCallbackProvider.builder().mcpClients(client).build(),
                client.getClientInfo().title(),
                evidence,
                decoder))
            .toArray();
    }
}
