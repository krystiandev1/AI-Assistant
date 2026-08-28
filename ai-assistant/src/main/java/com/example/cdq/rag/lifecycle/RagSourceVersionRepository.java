package com.example.cdq.rag.lifecycle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class RagSourceVersionRepository {

    private final JdbcTemplate jdbc;

    public RagSourceVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RagSourceVersion> ROW_MAPPER = (rs, i) -> new RagSourceVersion(
        rs.getLong("id"),
        rs.getLong("source_id"),
        rs.getString("source_hash"),
        rs.getString("pipeline_fingerprint"),
        VersionStatus.valueOf(rs.getString("status")),
        rs.getString("embedding_model"),
        rs.getInt("embedding_dimensions"),
        rs.getString("processor_version"),
        rs.getObject("chunk_count", Integer.class),
        rs.getObject("created_at", java.time.OffsetDateTime.class),
        rs.getObject("activated_at", java.time.OffsetDateTime.class),
        rs.getString("failure_reason")
    );

    public long insert(long sourceId, String sourceHash, String pipelineFingerprint,
                       PipelineConfig config) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                """
                INSERT INTO rag_source_version
                    (source_id, source_hash, pipeline_fingerprint, status,
                     embedding_model, embedding_dimensions, processor_version)
                VALUES (?, ?, ?, 'PROCESSING', ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sourceId);
            ps.setString(2, sourceHash);
            ps.setString(3, pipelineFingerprint);
            ps.setString(4, config.embeddingModel());
            ps.setInt(5, config.embeddingDimensions());
            ps.setString(6, config.processorVersion());
            return ps;
        }, keyHolder);
        return ((Number) keyHolder.getKeys().get("id")).longValue();
    }

    public Optional<RagSourceVersion> findById(long id) {
        var rows = jdbc.query(
            "SELECT * FROM rag_source_version WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean existsActiveWithFingerprint(String sourceKey, String pipelineFingerprint) {
        Integer count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rag_source_version rsv
            JOIN rag_source rs ON rs.id = rsv.source_id
            WHERE rs.source_key = ? AND rsv.pipeline_fingerprint = ? AND rsv.status = 'ACTIVE'
            """,
            Integer.class, sourceKey, pipelineFingerprint);
        return count != null && count > 0;
    }

    public void markFailed(long versionId, String reason) {
        jdbc.update(
            "UPDATE rag_source_version SET status = 'FAILED', failure_reason = ? WHERE id = ?",
            reason, versionId);
    }

    public void activate(long versionId, int chunkCount) {
        jdbc.update(
            "UPDATE rag_source_version SET status = 'ACTIVE', activated_at = NOW(), chunk_count = ? WHERE id = ?",
            chunkCount, versionId);
    }

    public void retireActive(long sourceId) {
        jdbc.update(
            "UPDATE rag_source_version SET status = 'RETIRED' WHERE source_id = ? AND status = 'ACTIVE'",
            sourceId);
    }

    public void activate(long versionId) {
        jdbc.update(
            "UPDATE rag_source_version SET status = 'ACTIVE', activated_at = NOW() WHERE id = ?",
            versionId);
    }
}
