CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS public.vector_store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1024)
);

CREATE INDEX IF NOT EXISTS vector_store_embedding_hnsw_idx
    ON public.vector_store
    USING HNSW (embedding vector_cosine_ops);
