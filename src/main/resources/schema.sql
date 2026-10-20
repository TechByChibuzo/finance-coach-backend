-- Runs at startup, before Hibernate creates or updates tables (spring.sql.init.mode=always).
-- transaction_embeddings.embedding is a vector(1536) column, which needs the pgvector extension.
CREATE EXTENSION IF NOT EXISTS vector;
