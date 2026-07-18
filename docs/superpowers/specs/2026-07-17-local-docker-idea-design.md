# Local Docker and IDEA Development Environment Design

## Goal

Provide a reproducible local development environment in which MySQL and PGVector run in Docker while `cn.bugstack.ai.Application` runs directly from IDEA with the `dev` Spring profile.

## Scope

- Add a root-level Compose file dedicated to local development.
- Run only MySQL 8 and PGVector because they are the databases required by the application startup path.
- Recreate only the volumes owned by this Compose project. Do not remove unrelated Docker volumes or containers.
- Initialize the current AI Agent schema and seed data automatically on a fresh MySQL volume.
- Initialize the PGVector extension and the `vector_store` table expected by `AiAgentConfig`.
- Make local database settings configurable through environment variables with working defaults.
- Document and script startup, shutdown, reset, and IDEA launch steps.

## Architecture

The Spring Boot process remains outside Docker and is started from IDEA. A root-level `compose.local.yml` supplies two infrastructure services on localhost:

- MySQL 8: host port `13306`, database `ai-agent-station-study`, user `root`, password `123456`.
- PGVector: host port `15432`, database `ai-rag-knowledge`, user `postgres`, password `123456`.

Named volumes persist both databases between normal restarts. The reset command removes only volumes created by this local Compose project and causes all initialization scripts to run again.

## MySQL Initialization

The Compose service mounts the existing root SQL files into `/docker-entrypoint-initdb.d` with deterministic numeric names:

1. `create_tables.sql` as schema initialization.
2. `init_data.sql` as core Agent and client seed data.
3. `init_intent_data.sql` as intent-classification seed data.
4. `init_react_data.sql` as ReAct seed data.

The obsolete `docs/dev-ops/mysql/sql/ai-agent-station-study.sql` is not used because it creates the unrelated `xfg_frame_archetype` employee schema.

## PGVector Initialization

The PostgreSQL container creates `ai-rag-knowledge` and enables the `vector` extension. Its initialization SQL creates the `vector_store` table used by `PgVectorStore`, with a `VECTOR(1024)` column matching `AiAgentConfig` and the configured `BAAI/bge-m3` embedding model.

The current obsolete `store_openai` and `vector_store_openai` tables with dimension `1536` are replaced in the local initialization path. Initialization must be idempotent on a fresh volume and must not drop tables during an ordinary restart.

## Spring Configuration

`application-dev.yml` uses environment-variable expressions with these local defaults:

- `MYSQL_HOST=127.0.0.1`
- `MYSQL_PORT=13306`
- `MYSQL_DATABASE=ai-agent-station-study`
- `MYSQL_USERNAME=root`
- `MYSQL_PASSWORD=123456`
- `PGVECTOR_HOST=127.0.0.1`
- `PGVECTOR_PORT=15432`
- `PGVECTOR_DATABASE=ai-rag-knowledge`
- `PGVECTOR_USERNAME=postgres`
- `PGVECTOR_PASSWORD=123456`
- `AI_AGENT_WORK_DIR` defaults to the current JVM working directory rather than a developer-specific absolute path.

The model API key remains externally configurable. Existing secret values must not be copied into documentation or new committed files.

## Operations

PowerShell-friendly commands or scripts provide four operations:

1. Start databases and wait for health checks.
2. Stop databases without deleting data.
3. Reset the local environment with `down --volumes` scoped to this Compose project, then start it again.
4. Display database status and the IDEA startup instructions.

Docker Desktop must be running before these operations. A missing Docker engine should produce a clear error instead of continuing.

## Verification

The completed environment is accepted when all of the following succeed:

- `docker compose config` validates the local Compose file.
- Both database containers report healthy.
- MySQL contains the `ai-agent-station-study` schema, all tables from `create_tables.sql`, and the required Agent seed records.
- PostgreSQL contains the `vector` extension and `vector_store.embedding` has dimension `1024`.
- `mvn -DskipTests package` succeeds.
- The Spring Boot application starts with the `dev` profile and listens on port `8091` without database connection or schema errors.

Tests that call paid or external model APIs are not part of local infrastructure acceptance. No model request is sent merely to verify database startup.

## Safety and Repository Hygiene

- Preserve all existing modified and untracked business files.
- Do not expose API keys or existing credentials in command output or documentation.
- Ignore generated logs and local environment override files.
- Do not commit application source changes unrelated to local Docker and IDEA startup.
