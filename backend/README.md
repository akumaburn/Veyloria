# Veyloria Backend

A high-performance, reactive backend for Veyloria built with Quarkus 3.36, SmallRye GraphQL, and integrated LLM support via llama.cpp.

## Architecture

- **Framework**: Quarkus 3.36 (CDI + Vert.x reactive core)
- **API**: SmallRye GraphQL (code-first, `@GraphQLApi`) with HTTP + WebSocket subscriptions
- **Database**: MySQL 8.0+ via Quarkus Reactive MySQL Client (Mutiny `Uni<T>` / `Multi<T>`)
- **Cache / Pub-Sub**: Redis 7 via Quarkus Redis Client
- **LLM**: llama.cpp server pool with lifecycle management (50+ chat templates) + optional OpenAI-compatible external API
- **Image Gen**: ComfyUI workflow builder + queue manager
- **Auth**: Custom JWT (auth0 `java-jwt`) with refresh tokens and Redis-backed revocation; request-scoped `GraphQLAuthContext` CDI bean
- **Monitoring**: Micrometer + Prometheus metrics, SmallRye Health

## Prerequisites

- Java 21+
- Gradle (bundled `./gradlew` wrapper — no system install required)
- MySQL 8.0+
- Redis 7+
- llama.cpp `llama-server` binary (optional; `LLM_MOCK_MODE=true` bypasses it)

## Quick Start

### 1. Database Setup

For a **new empty database only**: `schema.sql` contains DROP statements. Do not
apply it to an existing installation as an upgrade.

```bash
# Create database and user
mysql -u root -p <<'SQL'
CREATE DATABASE IF NOT EXISTS chatllm;
CREATE USER IF NOT EXISTS 'chatllm'@'localhost' IDENTIFIED BY 'chatllm';
GRANT ALL PRIVILEGES ON chatllm.* TO 'chatllm'@'localhost';
FLUSH PRIVILEGES;
SQL

# Apply the canonical fresh-install schema
mysql -u chatllm -p chatllm < src/main/resources/db/schema.sql
```

The schema lives in `src/main/resources/db/schema.sql` — **edit that file directly for schema changes**. Startup also applies bundled idempotent upgrades from `db/migrations/` through
`ApplicationLifecycle`; keep them consistent with the fresh-install schema. This
project does not use Flyway or Liquibase. Startup requires CREATE, ALTER, INDEX and REFERENCES on the application schema,
in addition to DML; fresh Compose volumes grant those privileges. Existing
volumes retain their previous grants and need a maintenance user to update them. Never run `scripts/reset-database.sh` for an upgrade: it drops
all application data.

### 2. Configure Application

Configuration is driven by `src/main/resources/application.properties` with environment-variable overrides. See that file for all properties and their defaults. Either:

- Copy `../.env.example` to `../.env`, edit, then export it from the repository root with `set -a; source .env; set +a` before entering `backend/`, or
- Export variables directly:

```bash
export DATABASE_USER=chatllm
export DATABASE_PASSWORD=chatllm
export JWT_SECRET=$(openssl rand -base64 48)
export LLM_MODEL_PATH=/path/to/your/model.gguf
export LLM_BINARY_PATH=/path/to/llama-server
```

### 3. Run in Development Mode (hot reload)

```bash
./gradlew quarkusDev
```

Quarkus dev mode reloads Java source changes on the next HTTP request and exposes a dev UI at `http://localhost:8300/q/dev/`.

Remote debug:
```bash
./gradlew quarkusDev -Ddebug=5005          # non-suspended
./gradlew quarkusDev -Ddebug=5005 -Dsuspend=true   # suspend until attach
```

### 4. Build and Run for Production

```bash
# Uber-jar (self-contained runner)
./gradlew clean build -x test

# The default package type in application.properties is already uber-jar.
java -jar build/chatllm-quarkus-3.0.0-runner.jar
```

## API Endpoints

| Purpose | URL |
|---------|-----|
| GraphQL HTTP | `http://localhost:8300/graphql` |
| GraphQL UI (dev only) | `http://localhost:8300/q/graphql-ui/` (toggle via `ENABLE_GRAPHQL_PLAYGROUND=true`) |
| WebSocket Subscriptions | `ws://localhost:8300/graphql` (protocols: `graphql-transport-ws`, `graphql-ws`) |
| Health | `http://localhost:8300/health` (`/health/live`, `/health/ready`) |
| Metrics (Prometheus) | `http://localhost:8300/metrics` |

## Project Structure

```
backend/
├── build.gradle.kts                          # Gradle Kotlin DSL and Quarkus plugin
├── settings.gradle.kts
├── gradle.properties                         # Pins Quarkus 3.36 platform version
├── gradlew / gradle/                         # Gradle wrapper
├── scripts/
│   └── reset-database.sh                     # Reset database from schema.sql
└── src/main/
    ├── java/com/eonmux/chatllm/
    │   ├── lifecycle/                        # ApplicationLifecycle (@Observes StartupEvent/ShutdownEvent)
    │   ├── config/                           # AppConfig (@ConfigProperty), RoleplayEventAddresses
    │   ├── agents/                           # Focused agents and pipeline orchestration
    │   ├── map/                              # Procedural terrain, roads and locations,
    │   │   ├── generation/                   # SimplexNoise, CulturalRegionMapper, NameGenerator, ...
    │   │   ├── models/                       # Biome, TerrainData, JourneyState, RoadSegment, ...
    │   │   ├── MapService.java               # Map CRUD and entity positioning
    │   │   ├── MapResolver.java              # @GraphQLApi map operations
    │   │   ├── JourneyService.java
    │   │   ├── TravelService.java
    │   │   └── TravelEventRoller.java
    │   ├── models/                           # Domain models and DTOs (domain + DTO + response)
    │   ├── services/                         # CDI domain services (+ auth/)
    │   │   └── auth/                         # JWTAuthProvider, AuthContext
    │   ├── graphql/                          # SmallRye GraphQL layer (@GraphQLApi, code-first)
    │   │   ├── auth/                         # GraphQLAuthContext (@RequestScoped)
    │   │   ├── resolvers/                    # Code-first resolvers (@Query / @Mutation / @Subscription)
    │   │   └── scalars/                      # CustomScalars, JsonObjectScalarAdapter
    │   ├── llm/                              # llama.cpp integration, GGUF parser, pool
    │   ├── comfyui/                          # Image-gen client + workflow builder
    │   ├── messaging/                        # Owned event-bus consumers, including AgentPipelineBridge
    │   └── utils/                            # Cache, validation, Base64, ServiceHelper
    └── resources/
        ├── application.properties            # Quarkus + chatllm.* configuration
        └── db/schema.sql                     # Canonical fresh-install schema
```

## GraphQL API Surface

Live schema verified 2026-11-22: **78 queries, 123 mutations and 40 subscriptions**.
Counts describe root fields, not frontend operation documents. Validate client
operations with `frontend/scripts/check-graphql.ts` against the running backend.

Schema is generated at startup from annotated Java classes. No GraphQL schema snapshot is checked in; the authoritative schema is produced from the annotated Java sources. The live schema can be inspected via the GraphQL UI (dev mode) or by querying `__schema` over HTTP.

## Features

### Chat
- Real-time WebSocket messaging with LLM token streaming
- Single and group character conversations (random, round-robin, weighted turn modes)
- Multi-user conversations with invitations and role-based permissions
- Message editing and encoded message transport, reactions, attachments, search
- Typing indicators, user presence, session management

### Roleplay Engine
- Focused encounter, travel, interaction and bookkeeping judgments; one validated result per request
- Awaited state changes before event narration, separate prose review, and stable resident greetings after arrival
- Run-scoped progress snapshots/subscriptions and generation cancellation with queue cleanup
- Transactional narrative battle rounds and permission-aware inventory operations
- See [atomic decisions](../docs/DEVELOPER_GUIDE.md#atomic-llm-decisions) for ordering, schemas and failure handling
- World state, character sheets, dice, story branching, parties, achievements
- Procedural world maps (terrain, roads, biomes, cultures) + journey/travel system
- ComfyUI image generation for scenes and characters
- Community publishing, story templates, knowledge/RAG, data export/import

### LLM Integration
- Multi-model llama.cpp server pool with dynamic lifecycle management
- External OpenAI-compatible API support (e.g. OpenRouter) via `LLM_EXTERNAL_SERVER=true`
- 50+ chat-template auto-detection (GGUF metadata → server API → filename)
- Configurable sampling (temperature, top-p/k, min-p, repeat penalty, Mirostat)
- Flash attention, KV cache quantization, speculative decoding, LoRA, RoPE/YaRN
- Per-agent model configuration
- `LLM_MOCK_MODE=true` for development/CI without a real llama-server

## Environment Variables

### Core
| Variable | Description | Default |
|----------|-------------|---------|
| `QUARKUS_HTTP_PORT` | HTTP server port | 8300 |
| `DATABASE_HOST` | MySQL host | localhost |
| `DATABASE_PORT` | MySQL port | 3306 |
| `DATABASE_NAME` | Database name | chatllm |
| `DATABASE_USER` | MySQL user | chatllm |
| `DATABASE_PASSWORD` | MySQL password | chatllm |
| `DATABASE_POOL_SIZE` | Reactive pool size | 20 |
| `REDIS_HOST` | Redis host | localhost |
| `REDIS_PORT` | Redis port | 6379 |
| `REDIS_PASSWORD` | Redis password | (empty) |
| `REDIS_DATABASE` | Redis DB index | 0 |
| `JWT_SECRET` | JWT signing secret (≥ 32 chars) | (change in production) |
| `JWT_ACCESS_TOKEN_EXPIRY` | Access-token TTL (ms) | 14 400 000 (4 h) |
| `JWT_REFRESH_TOKEN_EXPIRY` | Refresh-token TTL (ms) | 604 800 000 (7 d) |
| `ALLOWED_ORIGINS` | CORS origins (comma-sep) | `http://localhost:80,http://localhost:3000,http://localhost:5173` |
| `RATE_LIMIT_PER_MINUTE` | Global request cap | 600 |
| `ENABLE_GRAPHQL_PLAYGROUND` | Expose `/q/graphql-ui/` | false |
| `LOG_LEVEL` | `com.eonmux.chatllm.*` log level | DEBUG |

### LLM (excerpt; see `application.properties` for all properties)
| Variable | Description | Default |
|----------|-------------|---------|
| `LLM_ENABLED` | Enable LLM integration | true |
| `LLM_MOCK_MODE` | Return canned responses | false |
| `LLM_EXTERNAL_SERVER` | Use OpenAI-compatible HTTP endpoint instead of managed llama-server | false |
| `LLM_EXTERNAL_BASE_URL` | External API base URL | http://localhost:8080 |
| `LLM_EXTERNAL_MODEL` | External model id | auto |
| `LLM_EXTERNAL_API_KEY` | Bearer key for external API | (empty) |
| `LLM_BINARY_PATH` | Path to `llama-server` binary | (machine-specific) |
| `LLM_MODEL_PATH` | Path to GGUF model | (machine-specific) |
| `LLM_HOST` / `LLM_PORT` | Managed llama-server bind | 127.0.0.1 / 8080 |
| `LLM_CONTEXT_SIZE` | Context window (tokens) | 216 000 |
| `LLM_BATCH_SIZE` / `LLM_UBATCH_SIZE` | Batching | 2048 / 512 |
| `LLM_GPU_LAYERS` | GPU layers (`-1` = all) | -1 |
| `LLM_FLASH_ATTN` | Flash attention (`on`/`off`/`auto`) | on |
| `LLM_CACHE_TYPE_K` / `LLM_CACHE_TYPE_V` | KV cache quantization | q8_0 / q4_0 |
| `LLM_FIT` / `LLM_FIT_TARGET` / `LLM_FIT_CTX` | Auto-fit layers to VRAM | on / 512 / 4096 |
| `LLM_MLOCK` | Lock model in RAM | true |

See `application.properties` for the complete list including multi-GPU (`LLM_DEVICE`, `LLM_TENSOR_SPLIT`), prompt caching (`LLM_CACHE_RAM`, `LLM_CACHE_REUSE`), speculative decoding (`LLM_MODEL_DRAFT`, draft-specific params), RoPE/YaRN, LoRA, MoE expert offloading (`LLM_CPU_MOE`), constrained grammar/JSON-schema sampling, multi-modal vision projector (`LLM_MMPROJ`), CPU affinity/scheduling, and ComfyUI settings.

## Development

### Gradle tasks

```bash
./gradlew quarkusDev          # Dev mode with hot reload
./gradlew build -x test       # Build uber-jar
./gradlew test                # Unit and service tests
RUN_INVENTORY_DB_TESTS=true ./gradlew test  # Include disposable MySQL integration tests
./gradlew clean               # Clean build outputs
./gradlew dependencies        # Dependency tree
```

### Schema changes

Keep `src/main/resources/db/schema.sql` authoritative for fresh installs. Existing
installations receive the idempotent resources and guarded column additions
registered in `ApplicationLifecycle` at startup. Add upgrade coverage alongside
canonical schema changes. The reset script deletes all data and is reserved for
intentional resets. See [database maintenance](../docs/DEVELOPER_GUIDE.md#schema-changes).

### Testing GraphQL

```bash
# Query
curl -X POST http://localhost:8300/graphql \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"query":"{ me { id email displayName } }"}'

# Login mutation (returns accessToken + refreshToken)
curl -X POST http://localhost:8300/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"mutation { login(username:\"ada\", password:\"password\") { accessToken refreshToken } }"}'
```

### WebSocket subscriptions

Browsers cannot set custom headers on a `WebSocket`; authentication is carried in the `connection_init` payload (not the HTTP `Authorization` header):

```javascript
const ws = new WebSocket('ws://localhost:8300/graphql', ['graphql-transport-ws']);
ws.onopen = () => ws.send(JSON.stringify({
  type: 'connection_init',
  payload: { authorization: `Bearer ${token}` }
}));
```

Server-side, `GraphQLAuthContext` reads this via `Context.unwrap(DataFetchingEnvironment).getGraphQlContext().get("init-payload")`.

## Monitoring

- Liveness: `GET /health/live`
- Readiness: `GET /health/ready`
- Full health: `GET /health`
- Prometheus: `GET /metrics`

Grafana dashboards are provisioned from `../monitoring/grafana/` when running under `docker-compose` (Grafana on `:3005`, Prometheus on `:9091`).

## Security

- JWT auth with refresh-token rotation and Redis-backed revocation
- BCrypt (12 rounds) for password hashing
- Parameterized SQL via Reactive MySQL Client (no ORM, no string concat)
- Per-user / per-IP rate limiting (sliding window backed by Redis)
- CORS and allowed-origin allowlisting
- Validated Base64 transport when the input explicitly declares encoded message content

## Troubleshooting

| Symptom | Check |
|---------|-------|
| `Connection refused` on MySQL | `docker compose ps mysql` (or `docker-compose ps mysql` with v1); confirm port `3307` (host) ↔ `3306` (container) |
| `Connection refused` on Redis | `redis-cli ping` against the configured host/port |
| `LLM server not responding` | Verify `LLM_BINARY_PATH` and `LLM_MODEL_PATH` exist; for CI use `LLM_MOCK_MODE=true` |
| Port already in use | `lsof -iTCP:8300 -sTCP:LISTEN` |
| WebSocket auth fails silently | Ensure token is sent in `connection_init.payload.authorization` — custom HTTP headers are not supported by browser `WebSocket` |

## License

Copyright 2024-2026 Amir Farhad Eslampanah. [Apache-2.0](../LICENSE).
