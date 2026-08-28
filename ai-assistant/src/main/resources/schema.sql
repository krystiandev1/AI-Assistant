-- Stage 3: Document Lifecycle tables
-- NOTE: production would use Flyway/Liquibase for schema migrations.
-- schema.sql is additive (CREATE IF NOT EXISTS) — never drops existing data.

CREATE TABLE IF NOT EXISTS rag_source (
    id                BIGSERIAL     PRIMARY KEY,
    source_key        VARCHAR(255)  NOT NULL UNIQUE,
    source_url        VARCHAR(1024) NOT NULL,
    active_version_id BIGINT        -- nullable; no FK constraint in Stage 3 (enforced by application)
                                    -- FK to rag_source_version added via Flyway in Stage 4
);

CREATE TABLE IF NOT EXISTS rag_source_version (
    id                   BIGSERIAL    PRIMARY KEY,
    source_id            BIGINT       NOT NULL REFERENCES rag_source(id),
    source_hash          CHAR(64)     NOT NULL,
    pipeline_fingerprint CHAR(64)     NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING'
                             CHECK (status IN ('PROCESSING','ACTIVE','RETIRED','FAILED')),
    embedding_model      VARCHAR(255) NOT NULL,
    embedding_dimensions INTEGER      NOT NULL,
    processor_version    VARCHAR(50)  NOT NULL,
    chunk_count          INTEGER,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activated_at         TIMESTAMPTZ,
    failure_reason       TEXT
);

-- Enforces at most one ACTIVE version per source at the database level.
CREATE UNIQUE INDEX IF NOT EXISTS uidx_rag_source_version_active
    ON rag_source_version (source_id)
    WHERE status = 'ACTIVE';
