package com.example.cdq.rag.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentHasherTest {

    @Test
    void sha256_returns_64_hex_characters() {
        String hash = DocumentHasher.sha256Hex("hello");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void sha256_is_deterministic() {
        String h1 = DocumentHasher.sha256Hex("same input");
        String h2 = DocumentHasher.sha256Hex("same input");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void sha256_different_input_produces_different_hash() {
        assertThat(DocumentHasher.sha256Hex("aaa"))
            .isNotEqualTo(DocumentHasher.sha256Hex("bbb"));
    }

    @Test
    void normalize_replaces_crlf_with_lf() {
        String normalized = DocumentHasher.normalize("line1\r\nline2");
        assertThat(normalized).isEqualTo("line1\nline2");
    }

    @Test
    void normalize_replaces_cr_with_lf() {
        String normalized = DocumentHasher.normalize("line1\rline2");
        assertThat(normalized).isEqualTo("line1\nline2");
    }

    @Test
    void crlf_and_lf_documents_produce_same_hash() {
        String withCrlf = "Hello World\r\nSecond line\r\n";
        String withLf   = "Hello World\nSecond line\n";
        assertThat(DocumentHasher.sha256Hex(DocumentHasher.normalize(withCrlf)))
            .isEqualTo(DocumentHasher.sha256Hex(DocumentHasher.normalize(withLf)));
    }

    @Test
    void trailing_whitespace_at_end_of_file_does_not_change_hash() {
        String noTrailing   = "content line";
        String withTrailing = "content line\n\n   \n";
        assertThat(DocumentHasher.sha256Hex(DocumentHasher.normalize(noTrailing)))
            .isEqualTo(DocumentHasher.sha256Hex(DocumentHasher.normalize(withTrailing)));
    }
}
