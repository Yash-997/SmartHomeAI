-- pgvector (the extension is created by infra/postgres/init/01-pgvector.sql on a fresh volume; this makes sure
-- it also exists on a database that was created earlier).
CREATE EXTENSION IF NOT EXISTS vector;

-- No fixed dimension, so switching embedding models never needs a schema change (rows record their own model and
-- dimension and are only compared with query vectors of the same kind). Searches are exact (no approximate index):
-- each user's own corpus is small, and exact search never silently misses a closest match.
ALTER TABLE rag_chunks ADD COLUMN embedding vector;
