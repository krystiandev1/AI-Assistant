CREATE TABLE IF NOT EXISTS knowledge_source_state (
    source_id   VARCHAR(255) PRIMARY KEY,
    source_hash VARCHAR(64)  NOT NULL,
    ingested_at TIMESTAMP WITH TIME ZONE NOT NULL
);
