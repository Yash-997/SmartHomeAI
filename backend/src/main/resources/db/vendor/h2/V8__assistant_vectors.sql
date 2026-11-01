-- Test database only (H2 has no vector type). The vector is stored as text and compared in Java.
-- Production uses db/vendor/postgresql, where this column is a pgvector "vector".
ALTER TABLE rag_chunks ADD COLUMN embedding VARCHAR(200000);
