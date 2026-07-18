# Local Docker and IDEA Development Environment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run MySQL and PGVector in a reproducible local Docker environment and start the Spring Boot application directly from IDEA with matching development configuration.

**Architecture:** A root `compose.local.yml` owns two named volumes and exposes MySQL on `13306` and PGVector on `15432`. Existing project SQL initializes MySQL in a deterministic order, a corrected PGVector script initializes a 1024-dimensional `vector_store`, and `application-dev.yml` uses overridable environment variables with local defaults.

**Tech Stack:** Docker Compose, MySQL 8.0.32, PostgreSQL/PGVector, PowerShell, Spring Boot 3.4.3, Spring AI 1.0.0, Maven, Java 17.

## Global Constraints

- Delete only containers and volumes owned by Compose project `ai-agent-station-study-local`.
- Preserve all existing modified, staged, and untracked business files.
- Use local development password `123456` for both databases.
- Do not print, copy, or commit an existing model API key.
- Do not send an external model request during infrastructure verification.
- Treat configuration validation and live container checks as the test-first acceptance mechanism for these configuration-only changes.

---

### Task 1: Define the local database services and PGVector schema

**Files:**
- Create: `compose.local.yml`
- Modify: `docs/dev-ops/pgvector/sql/init.sql`

**Interfaces:**
- Consumes: root MySQL scripts `create_tables.sql`, `init_data.sql`, `init_intent_data.sql`, and `init_react_data.sql`.
- Produces: healthy `mysql` and `pgvector` Compose services on localhost ports `13306` and `15432`.

- [ ] **Step 1: Verify the Compose definition does not exist yet**

Run:

```powershell
docker compose -f compose.local.yml config
```

Expected: FAIL because `compose.local.yml` does not exist.

- [ ] **Step 2: Create the minimal local Compose definition**

Create `compose.local.yml`:

```yaml
name: ai-agent-station-study-local

services:
  mysql:
    image: mysql:8.0.32
    container_name: ai-agent-local-mysql
    restart: unless-stopped
    command:
      - --default-authentication-plugin=mysql_native_password
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    environment:
      TZ: Asia/Shanghai
      MYSQL_ROOT_PASSWORD: "123456"
    ports:
      - "13306:3306"
    volumes:
      - local_mysql_data:/var/lib/mysql
      - ./create_tables.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro
      - ./init_data.sql:/docker-entrypoint-initdb.d/02-core-data.sql:ro
      - ./init_intent_data.sql:/docker-entrypoint-initdb.d/03-intent-data.sql:ro
      - ./init_react_data.sql:/docker-entrypoint-initdb.d/04-react-data.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -uroot -p$${MYSQL_ROOT_PASSWORD} --silent"]
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 20s

  pgvector:
    image: pgvector/pgvector:pg16
    container_name: ai-agent-local-pgvector
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
      POSTGRES_DB: ai-rag-knowledge
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: "123456"
    ports:
      - "15432:5432"
    volumes:
      - local_pgvector_data:/var/lib/postgresql/data
      - ./docs/dev-ops/pgvector/sql/init.sql:/docker-entrypoint-initdb.d/01-vector-store.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d ai-rag-knowledge"]
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 10s

volumes:
  local_mysql_data:
  local_pgvector_data:
```

- [ ] **Step 3: Correct the PGVector initialization schema**

Replace `docs/dev-ops/pgvector/sql/init.sql` with:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS public.vector_store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSON,
    embedding VECTOR(1024)
);

CREATE INDEX IF NOT EXISTS vector_store_embedding_hnsw_idx
    ON public.vector_store
    USING HNSW (embedding vector_cosine_ops);
```

- [ ] **Step 4: Validate the Compose model**

Run:

```powershell
docker compose -f compose.local.yml config --quiet
```

Expected: exit code `0` with no schema errors.

- [ ] **Step 5: Commit only the local database definition**

```powershell
git add -- compose.local.yml docs/dev-ops/pgvector/sql/init.sql
git commit --only -m "build: add local mysql and pgvector environment" -- compose.local.yml docs/dev-ops/pgvector/sql/init.sql
```

Expected: the commit contains exactly these two files; pre-existing staged business changes remain staged.

---

### Task 2: Align Spring development configuration with Docker

**Files:**
- Modify: `ai-agent-station-study-app/src/main/resources/application-dev.yml`

**Interfaces:**
- Consumes: Compose host ports and credentials produced by Task 1.
- Produces: Spring properties for `DataSourceConfig`, `AiAgentConfig`, and `ReActToolProperties` that work from IDEA and remain overridable.

- [ ] **Step 1: Verify current configuration does not target the new local ports**

Run:

```powershell
$yaml = Get-Content -Raw ai-agent-station-study-app/src/main/resources/application-dev.yml
if ($yaml -notmatch 'MYSQL_PORT:13306' -or $yaml -notmatch 'PGVECTOR_PORT:15432') { throw 'Expected failure: local Docker defaults are not configured' }
```

Expected: FAIL with `Expected failure: local Docker defaults are not configured`.

- [ ] **Step 2: Replace database settings with environment-variable expressions**

In `application-dev.yml`, set the datasource block to:

```yaml
spring:
  datasource:
    mysql:
      username: ${MYSQL_USERNAME:root}
      password: ${MYSQL_PASSWORD:123456}
      url: jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:13306}/${MYSQL_DATABASE:ai-agent-station-study}?useUnicode=true&characterEncoding=utf8&autoReconnect=true&zeroDateTimeBehavior=convertToNull&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
      driver-class-name: com.mysql.cj.jdbc.Driver
      type: com.zaxxer.hikari.HikariDataSource
      hikari:
        pool-name: MainHikariPool
        minimum-idle: 5
        idle-timeout: 180000
        maximum-pool-size: 15
        auto-commit: true
        max-lifetime: 1800000
        connection-timeout: 30000
        connection-test-query: SELECT 1
    pgvector:
      driver-class-name: org.postgresql.Driver
      username: ${PGVECTOR_USERNAME:postgres}
      password: ${PGVECTOR_PASSWORD:123456}
      url: jdbc:postgresql://${PGVECTOR_HOST:127.0.0.1}:${PGVECTOR_PORT:15432}/${PGVECTOR_DATABASE:ai-rag-knowledge}
      type: com.zaxxer.hikari.HikariDataSource
      hikari:
        maximum-pool-size: 5
        minimum-idle: 2
        idle-timeout: 30000
        connection-timeout: 30000
```

Keep the existing `spring.ai` block, but change its API-key entry without revealing the previous value:

```yaml
      api-key: ${OPENAI_API_KEY:local-development-key}
```

Change the ReAct working directory to:

```yaml
        work-dir: ${AI_AGENT_WORK_DIR:${user.dir}}
```

- [ ] **Step 3: Verify property names and ensure no developer-specific path remains**

Run:

```powershell
$yaml = Get-Content -Raw ai-agent-station-study-app/src/main/resources/application-dev.yml
if ($yaml -notmatch '\$\{MYSQL_PORT:13306\}') { throw 'MySQL port default missing' }
if ($yaml -notmatch '\$\{PGVECTOR_PORT:15432\}') { throw 'PGVector port default missing' }
if ($yaml -match 'D:/javacode/ai-agent/') { throw 'Developer-specific work directory remains' }
if ($yaml -notmatch '\$\{OPENAI_API_KEY:local-development-key\}') { throw 'External API-key property missing' }
```

Expected: exit code `0`.

- [ ] **Step 4: Compile the application configuration**

Run:

```powershell
mvn -pl ai-agent-station-study-app -am -DskipTests package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit only the development configuration**

```powershell
git add -- ai-agent-station-study-app/src/main/resources/application-dev.yml
git commit --only -m "config: align dev profile with local docker" -- ai-agent-station-study-app/src/main/resources/application-dev.yml
```

Expected: the commit contains only `application-dev.yml`.

---

### Task 3: Add safe local environment operations and documentation

**Files:**
- Create: `scripts/local-env.ps1`
- Create: `docs/local-development.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `compose.local.yml` from Task 1.
- Produces: `start`, `stop`, `reset`, `status`, and `verify` commands with a Docker engine preflight check.

- [ ] **Step 1: Verify the operations script is absent**

Run:

```powershell
& ./scripts/local-env.ps1 status
```

Expected: FAIL because the script does not exist.

- [ ] **Step 2: Create the PowerShell operations script**

Create `scripts/local-env.ps1`:

```powershell
param(
    [ValidateSet('start', 'stop', 'reset', 'status', 'verify')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $ProjectRoot 'compose.local.yml'

function Assert-DockerEngine {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker engine is unavailable. Start Docker Desktop and retry.'
    }
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker compose -f $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($Arguments -join ' ')"
    }
}

function Verify-Databases {
    Invoke-Compose exec -T mysql mysql -uroot -p123456 -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ai-agent-station-study';"
    Invoke-Compose exec -T mysql mysql -uroot -p123456 -N -D ai-agent-station-study -e "SELECT COUNT(*) FROM ai_agent;"
    Invoke-Compose exec -T pgvector psql -U postgres -d ai-rag-knowledge -tAc "SELECT extversion FROM pg_extension WHERE extname='vector';"
    Invoke-Compose exec -T pgvector psql -U postgres -d ai-rag-knowledge -tAc "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a WHERE a.attrelid='public.vector_store'::regclass AND a.attname='embedding';"
}

Assert-DockerEngine

switch ($Action) {
    'start' {
        Invoke-Compose up -d --wait
        Verify-Databases
    }
    'stop' {
        Invoke-Compose down
    }
    'reset' {
        Invoke-Compose down --volumes --remove-orphans
        Invoke-Compose up -d --wait
        Verify-Databases
    }
    'status' {
        Invoke-Compose ps
    }
    'verify' {
        Verify-Databases
    }
}
```

- [ ] **Step 3: Add repository hygiene rules**

Append to `.gitignore`:

```gitignore

### Local runtime ###
*.log
**/data/log/
.env
.env.local
```

- [ ] **Step 4: Write IDEA and Docker usage documentation**

Create `docs/local-development.md`:

```markdown
# Local development

## Prerequisites

- Java 17
- Maven 3.9+
- Docker Desktop with the Linux engine running
- IntelliJ IDEA

## Initialize databases

From the repository root:

```powershell
./scripts/local-env.ps1 reset
```

This deletes and recreates only the volumes owned by `ai-agent-station-study-local`.

For later use:

```powershell
./scripts/local-env.ps1 start
./scripts/local-env.ps1 status
./scripts/local-env.ps1 verify
./scripts/local-env.ps1 stop
```

## Run from IDEA

1. Import the root `pom.xml` as a Maven project.
2. Select JDK 17 for the project and Maven runner.
3. Run `cn.bugstack.ai.Application` from module `ai-agent-station-study-app`.
4. Keep the default `dev` profile, or add VM option `-Dspring.profiles.active=dev`.
5. Set `OPENAI_API_KEY` in the IDEA run configuration before making embedding or chat requests.

The application listens on `http://localhost:8091`.

Local database defaults are MySQL `127.0.0.1:13306` and PGVector `127.0.0.1:15432`, both using password `123456`.
```

- [ ] **Step 5: Verify the missing-Docker error or current Compose status**

Run:

```powershell
./scripts/local-env.ps1 status
```

Expected when Docker Desktop is stopped: the command fails with `Docker engine is unavailable. Start Docker Desktop and retry.` Expected when it is running: the Compose service table is displayed.

- [ ] **Step 6: Commit only operations and documentation files**

```powershell
git add -- scripts/local-env.ps1 docs/local-development.md .gitignore
git commit --only -m "docs: add local environment operations" -- scripts/local-env.ps1 docs/local-development.md .gitignore
```

Expected: the commit contains exactly these three files.

---

### Task 4: Reset databases and verify the IDEA-equivalent application startup

**Files:**
- No new production files.
- Inspect: `ai-agent-station-study-app/target/ai-agent-station-study-app.jar`

**Interfaces:**
- Consumes: all artifacts from Tasks 1-3 and a running Docker Desktop engine.
- Produces: fresh database volumes and evidence that the packaged application starts with the same `dev` configuration used by IDEA.

- [ ] **Step 1: Confirm Docker Desktop is running**

Run:

```powershell
docker info
```

Expected: exit code `0`. If it fails, pause and ask the user to start Docker Desktop; do not attempt deletion.

- [ ] **Step 2: Recreate only the local project volumes**

Run:

```powershell
./scripts/local-env.ps1 reset
```

Expected: both `ai-agent-local-mysql` and `ai-agent-local-pgvector` become healthy, and verification prints a nonzero MySQL table count, a nonzero Agent count, a PGVector extension version, and `vector(1024)`.

- [ ] **Step 3: Verify exact MySQL schema and seed records**

Run:

```powershell
docker compose -f compose.local.yml exec -T mysql mysql -uroot -p123456 -D ai-agent-station-study -e "SHOW TABLES; SELECT agent_id, agent_name, status FROM ai_agent; SELECT client_id, client_name, status FROM ai_client ORDER BY client_id;"
```

Expected: all tables from `create_tables.sql` exist and the configured Agent/client rows are returned.

- [ ] **Step 4: Verify the exact PGVector table definition**

Run:

```powershell
docker compose -f compose.local.yml exec -T pgvector psql -U postgres -d ai-rag-knowledge -c "\d+ public.vector_store"
```

Expected: `embedding` is `vector(1024)` and the HNSW cosine index exists.

- [ ] **Step 5: Build the complete reactor**

Run:

```powershell
mvn -DskipTests package
```

Expected: all seven reactor entries report `SUCCESS` and Maven reports `BUILD SUCCESS`.

- [ ] **Step 6: Start the packaged application without calling a model**

Run in a PowerShell terminal:

```powershell
$env:OPENAI_API_KEY = 'local-development-key'
java -jar ai-agent-station-study-app/target/ai-agent-station-study-app.jar --spring.profiles.active=dev
```

Expected: Spring Boot starts on port `8091` without MySQL, PostgreSQL, MyBatis, or PGVector schema errors. Stop it with `Ctrl+C` after startup verification.

- [ ] **Step 7: Verify the HTTP process is listening**

While the application is running, execute in a second terminal:

```powershell
Test-NetConnection -ComputerName 127.0.0.1 -Port 8091 -InformationLevel Quiet
```

Expected: `True`.

- [ ] **Step 8: Review repository state**

Run:

```powershell
git status --short
git log -4 --oneline
```

Expected: no implementation-generated logs are tracked, the three scoped implementation commits and design commit are visible, and all pre-existing business changes remain intact.
