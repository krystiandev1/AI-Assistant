package com.example.cdq.evidence;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolResultDecoderTest {

    private final McpToolResultDecoder decoder = new McpToolResultDecoder(new ObjectMapper());

    @Test
    void ok_status_returns_ok_outcome() {
        String raw = """
            [{"type":"text","text":"{\\"status\\":\\"OK\\",\\"city\\":\\"Berlin\\",\\"temperatureCelsius\\":18.2}"}]
            """;

        DecodedToolResult result = decoder.decode(raw);

        assertThat(result.outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void error_status_returns_error_outcome_with_code() {
        String raw = """
            [{"type":"text","text":"{\\"status\\":\\"ERROR\\",\\"errorCode\\":\\"COUNTRY_NOT_FOUND\\",\\"message\\":\\"...\\"}"}]
            """;

        DecodedToolResult result = decoder.decode(raw);

        assertThat(result.outcome()).isEqualTo(ToolOutcome.ERROR);
        assertThat(result.errorCode()).isEqualTo("COUNTRY_NOT_FOUND");
    }

    @Test
    void malformed_envelope_not_array_returns_unknown() {
        DecodedToolResult result = decoder.decode("{\"type\":\"text\",\"text\":\"...\"}");

        assertThat(result.outcome()).isEqualTo(ToolOutcome.UNKNOWN);
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void non_text_content_type_skipped_returns_unknown() {
        String raw = """
            [{"type":"image","data":"base64stuff"}]
            """;

        DecodedToolResult result = decoder.decode(raw);

        assertThat(result.outcome()).isEqualTo(ToolOutcome.UNKNOWN);
    }

    @Test
    void unrecognized_status_value_returns_unknown() {
        String raw = """
            [{"type":"text","text":"{\\"status\\":\\"PENDING\\"}"}]
            """;

        DecodedToolResult result = decoder.decode(raw);

        assertThat(result.outcome()).isEqualTo(ToolOutcome.UNKNOWN);
    }

    @Test
    void invalid_json_returns_unknown_without_throwing() {
        DecodedToolResult result = decoder.decode("not valid json at all");

        assertThat(result.outcome()).isEqualTo(ToolOutcome.UNKNOWN);
    }

    @Test
    void inner_text_not_json_returns_unknown_without_throwing() {
        String raw = """
            [{"type":"text","text":"plain text, not json"}]
            """;

        DecodedToolResult result = decoder.decode(raw);

        assertThat(result.outcome()).isEqualTo(ToolOutcome.UNKNOWN);
    }
}
