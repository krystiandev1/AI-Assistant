package com.example.cdq.evidence;

import org.junit.jupiter.api.Test;

import static com.example.cdq.evidence.ToolInvocationStatus.COMPLETED;
import static com.example.cdq.evidence.ToolInvocationStatus.FAILED;
import static com.example.cdq.evidence.ToolOutcome.ERROR;
import static com.example.cdq.evidence.ToolOutcome.OK;
import static com.example.cdq.evidence.ToolOutcome.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceAccumulatorTest {

    @Test
    void records_tool_calls_in_order() {
        var acc = new EvidenceAccumulator("req-1");

        acc.recordToolCall("countries", "get_country", "{\"countryName\":\"Germany\"}",
            COMPLETED, OK, null);
        acc.recordToolCall("weather", "get_weather", "{\"city\":\"Berlin\"}",
            COMPLETED, OK, null);

        ExecutionEvidence evidence = acc.build();

        assertThat(evidence.toolCalls()).hasSize(2);
        assertThat(evidence.toolCalls().get(0).tool()).isEqualTo("get_country");
        assertThat(evidence.toolCalls().get(1).tool()).isEqualTo("get_weather");
        assertThat(evidence.toolCalls().get(1).argumentsJson()).contains("Berlin");
    }

    @Test
    void failed_invocation_records_correctly() {
        var acc = new EvidenceAccumulator("req-2");

        acc.recordToolCall("weather", "get_weather", "{}",
            FAILED, UNKNOWN, "MCP_INVOCATION_FAILED");

        ExecutionEvidence evidence = acc.build();
        ToolCallEvidence call = evidence.toolCalls().get(0);

        assertThat(call.invocationStatus()).isEqualTo(FAILED);
        assertThat(call.outcome()).isEqualTo(UNKNOWN);
        assertThat(call.errorCode()).isEqualTo("MCP_INVOCATION_FAILED");
    }

    @Test
    void completed_with_business_error_records_correctly() {
        var acc = new EvidenceAccumulator("req-3");

        acc.recordToolCall("countries", "get_country", "{\"countryName\":\"Narnia\"}",
            COMPLETED, ERROR, "COUNTRY_NOT_FOUND");

        ExecutionEvidence evidence = acc.build();
        ToolCallEvidence call = evidence.toolCalls().get(0);

        assertThat(call.invocationStatus()).isEqualTo(COMPLETED);
        assertThat(call.outcome()).isEqualTo(ERROR);
        assertThat(call.errorCode()).isEqualTo("COUNTRY_NOT_FOUND");
    }

    @Test
    void records_rag_documents() {
        var acc = new EvidenceAccumulator("req-4");

        acc.recordRagDocument("cdq-fraud-guard",
            "https://www.cdq.com/products/cdq-fraud-guard",
            "Bank Account Verification", 2, 42L);

        ExecutionEvidence evidence = acc.build();

        assertThat(evidence.ragDocuments()).hasSize(1);
        assertThat(evidence.ragDocuments().get(0).section()).isEqualTo("Bank Account Verification");
        assertThat(evidence.ragDocuments().get(0).chunkIndex()).isEqualTo(2);
        assertThat(evidence.ragDocuments().get(0).sourceVersionId()).isEqualTo(42L);
    }

    @Test
    void empty_accumulator_returns_empty_evidence() {
        var acc = new EvidenceAccumulator("req-5");
        ExecutionEvidence evidence = acc.build();

        assertThat(evidence.toolCalls()).isEmpty();
        assertThat(evidence.ragDocuments()).isEmpty();
    }

    @Test
    void built_evidence_lists_are_unmodifiable() {
        var acc = new EvidenceAccumulator("req-6");
        acc.recordToolCall("countries", "get_country", "{}", COMPLETED, OK, null);

        ExecutionEvidence evidence = acc.build();

        assertThat(evidence.toolCalls()).satisfies(list ->
            org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> list.add(null)
            )
        );
    }
}
