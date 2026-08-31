package com.example.cdq.rag.lifecycle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

@Repository
public class RagSourceRepository {

    private final JdbcTemplate jdbc;

    public RagSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RagSource> ROW_MAPPER = (rs, i) -> new RagSource(
        rs.getLong("id"),
        rs.getString("source_key"),
        rs.getString("source_url"),
        rs.getObject("active_version_id", Long.class)
    );

    public Optional<RagSource> findBySourceKey(String sourceKey) {
        var rows = jdbc.query(
            "SELECT id, source_key, source_url, active_version_id FROM rag_source WHERE source_key = ?",
            ROW_MAPPER, sourceKey);
        return rows.stream().findFirst();
    }

    /**
     * Inserts a new rag_source row if source_key does not exist yet, then returns the row.
     */
    public RagSource upsert(String sourceKey, String sourceUrl) {
        jdbc.update(
            "INSERT INTO rag_source (source_key, source_url) VALUES (?, ?) ON CONFLICT (source_key) DO NOTHING",
            sourceKey, sourceUrl);
        return findBySourceKey(sourceKey).orElseThrow(
            () -> new IllegalStateException("rag_source row missing after upsert: " + sourceKey));
    }

    public void setActiveVersion(long sourceId, long versionId) {
        jdbc.update(
            "UPDATE rag_source SET active_version_id = ? WHERE id = ?",
            versionId, sourceId);
    }

    public Long findActiveVersionId(String sourceKey) {
        var rows = jdbc.query(
            "SELECT active_version_id FROM rag_source WHERE source_key = ?",
            (rs, i) -> rs.getObject("active_version_id", Long.class),
            sourceKey);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
