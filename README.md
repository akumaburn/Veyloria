# Veyloria

Veyloria is a multiplayer, AI-run fantasy RPG. Conversations, maps, inventories,
and combat share persistent world state across sessions. State advances during
conversation rounds and journey steps; there is no background world simulation.

Everything runs through one roleplay engine: AI characters, world state, character
sheets, dice rolls, procedural maps, travel, image generation, and stories the
community shapes together. The stack is a Quarkus 3.36 backend and a React 18
frontend, with inference on a local llama.cpp server pool or an OpenAI-compatible
endpoint.

## Product tour

[![Watch the Veyloria product tour on YouTube](https://img.youtube.com/vi/EWhdoN4t6pI/hqdefault.jpg)](https://youtu.be/EWhdoN4t6pI)

Watch the guided tour of Veyloria’s characters, conversations, voices, maps and RPG systems.

## Features

### Chat
- Real-time WebSocket messaging with LLM token streaming
- Single and group character conversations (random, round-robin, weighted turn modes)
- Multi-user conversations with invitations and role-based permissions
- Message editing (Base64 encoding), reactions, attachments, search
- Typing indicators, user presence, session management

### Roleplay Engine
- **AI Agent Pipeline**: Multi-agent system (Highlighter, Muse, Accountant, Narrator, Skeptic, Casting Director) with RAG-augmented context retrieval for narrative generation
- **World State**: Scenes, locations, time progression, weather, mood and flags; authoring controls require narrator mode
- **Character Sheets**: Stats, relationships, and permission-aware inventory with use, equip/unequip and partial-stack gifts ([item rules](docs/inventory.md))
- **Dice System**: Configurable dice rolls with history
- **Story Branching**: Fork conversations at any bookmark point
- **Procedural World Maps**: Simplex noise terrain with 4 shape modes (island, continent, archipelago, coast), 4 themes (temperate, desert, arctic, tropical), 14 Whittaker biomes, 3 map scales (city, region, continent) with configurable octave detail; A\*-pathfound road networks (MST-connected main roads + trails), 22 location types with biome-aware placement constraints; 8 procedural cultures with Voronoi region mapping, phoneme-based name generation (4 strategies), and contextual multi-layer descriptions; 2D canvas and ray-marched 3D (Three.js) visualization with fog of war
- **Journey System**: Persistent waypoint travel, paused encounters, validated local movement, arrival narration and local resident greetings ([maps](docs/world-and-local-maps.md))
- **Narrative Combat**: Party voting and custom attempts, separate adjudication/prose/choices, atomic round effects, and fight links from the map back to chat ([combat](docs/narrative-combat.md))
- **Narrator Mode**: Optional authoring controls shared by chat and map; normal player mode hides world/state overrides, message rewriting and AI internals
- **Party System**: Create parties, invite members, manage party state
- **Achievements**: XP progression, tiered achievements, user levels
- **Image Generation**: ComfyUI integration for scene/character illustrations
- **Community**: Publish story listings and synopses, rate and browse, share templates. Public transcript reading is not implemented yet.
- **Story Templates**: 18+ built-in narrative frameworks (fantasy, sci-fi, horror, mystery, romance, adventure, historical, survival, slice-of-life, freeform, dark fantasy, superhero, western, steampunk, psychological thriller, mythology, and more)
- **Knowledge/RAG**: Knowledge sieve and retrieval service for context-aware agent responses
- **Data Export/Import**: User data management and portability

### LLM Integration
- llama.cpp multi-model server pool with dynamic lifecycle management
- External OpenAI-compatible API support (OpenRouter, etc.) via `LLM_EXTERNAL_SERVER`
- Auto-detection of 50+ chat templates from GGUF metadata, server API, or filename
- GGUF model metadata parsing
- Configurable generation parameters (temperature, top\_p, top\_k, min\_p, repeat\_penalty, Mirostat, etc.)
- Advanced features: flash attention, KV cache quantization, speculative decoding, LoRA adapters, RoPE/YaRN context scaling
- Per-agent model configuration
- Mock LLM mode for development/testing

### Infrastructure
- JWT authentication with refresh tokens and Redis-backed revocation
- Redis-based rate limiting (sliding window, configurable window/max requests)
- Prometheus metrics (Micrometer) and Grafana dashboards
- Health checks (Kubernetes-compatible)
- Docker Compose with optional LLM profile
- Ming Voice Design playback with narrator/character switching ([setup and architecture](docs/voice-playback.md)), markdown rendering, syntax highlighting
- WebSocket authentication via `connection_init` payload
- Apollo Client with automatic token refresh and subscriber queue
- GraphQL DataLoader factory for N+1 query prevention

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Quarkus 3.36, Gradle (Kotlin DSL via `./gradlew` wrapper) |
| API | SmallRye GraphQL (code-first) with HTTP + WebSocket |
| Database | MySQL 8.0+ (no ORM, reactive parameterized SQL via Mutiny) |
| Cache/PubSub | Redis 7 (Quarkus Redis client) |
| Auth | JWT (auth0 java-jwt 4.5.2) + BCrypt |
| LLM | llama.cpp (multi-model server pool) + OpenAI-compatible API |
| Image Gen | ComfyUI (workflow builder + queue manager) |
| Frontend | React 18.3, TypeScript 5.9, Vite 8 |
| Runtime | Bun 1.4.5+; Node.js 24 LTS for development and test tooling |
| State | Zustand 4.5 + Immer |
| GraphQL Client | Apollo Client 3.14 |
| 3D Maps | Three.js 0.182 + @react-three/fiber + @react-three/drei |
| Styling | Tailwind CSS 4 + Radix UI + Lucide |
| Routing | React Router 7 |
| Monitoring | Prometheus (Micrometer) + Grafana |
| Testing | JUnit 5 + Mockito + Testcontainers (backend), Vitest + Testing Library (frontend) |

## Architecture

```
+-------------------------------------------------------------+
|                         Frontend                            |
|  React 18 + TypeScript + Apollo Client + Zustand + Tailwind |
|  Three.js (2D/3D global maps, 2D local layouts)             |
|  Runtime: Bun                                               |
+----------------------+--------------------------------------+
                       | GraphQL (HTTP/WebSocket)
+----------------------v--------------------------------------+
|                    Backend (Quarkus)                         |
|                                                             |
|  +---------------+  +--------------+  +----------------+   |
|  | SmallRye      |  |   CDI        |  |   AI Agent     |   |
|  | GraphQL API   |<-|  Services    |->|   Pipeline     |   |
|  +---------------+  +------+-------+  +-------+--------+   |
|                            |                  |             |
|  +---------------+  +------+-------+  +-------+--------+   |
|  | Reactive      |  |   Quarkus   |  |  Map Gen /     |   |
|  | MySQL Client  |  | Redis Client|  |  LLM / RAG     |   |
|  +---------------+  +--------------+  +----------------+   |
+------------------+-------------+-------------+--------------+
                   |             |             |
       +-----------v--+  +------v------+  +---v----------+
       |    MySQL     |  |   Redis    |  |  llama.cpp   |
       +--------------+  +------------+  +--------------+
```

### AI Agent Pipeline

Ordinary rounds finish the scheduled character replies before resolving the
world. Highlighter/RAG prepare context; separate calls check an encounter,
travel, and (when there was no travel) an interaction. Validated travel updates
the authoritative stage before narration. The Accountant then collects one
inventory, stat, relationship or world operation per request and awaits service
application. The narrator writes confirmed events, the Skeptic reviews prose,
and required arrival/result blocks are saved before the player turn reopens.
Staffed arrivals add a separate resident greeting after arrival narration.
Optional Muse suggestions and asynchronous casting cannot authorize state changes.

Initialization creates the map and starting stage before opening narration.
Combat separately adjudicates an attempt, writes its consequences and generates
choices only for an ongoing fight. See [atomic decisions and exact ordering](docs/DEVELOPER_GUIDE.md#atomic-llm-decisions).

### Procedural Map Generation

```
Seed + Config (scale, theme, water level)
     |
     v
TerrainGenerator --> heightmap + biome grid
  |  Simplex noise FBM (4-7 octaves by scale)
  |  4 shape modes: island, continent, archipelago, coast
  |  14 Whittaker biomes (elevation x moisture x temperature)
     |
     v
RoadGenerator --> MST + A* pathfinding -> road segments (main roads + trails)
     |
     v
LocationPlacer
  +-- CulturalRegionMapper --> Voronoi regions with 8 biome-affinity cultures
  +-- LocationTypeRegistry --> 22 types with biome/elevation constraints
  +-- NameGenerator --> 4 strategies (syllabic, compound, descriptive, possessive)
  +-- DescriptionGenerator --> multi-layer contextual flavor text
     |
     v
Frontend Rendering
  +-- 2D: Canvas-based minimap
  +-- 3D: Ray-marched GLSL shader (single full-screen quad pass)
       +-- Terrain surface + water plane + sky dome + clouds + atmospheric fog
       +-- Heightmap as DataTexture (RED format, FloatType)
       +-- Culture overlays via Voronoi shader uniforms (up to 16 regions)
       +-- gl_FragDepth for natural depth-testing with Three.js markers
```

### Journey/Travel System

```
Start Journey (origin -> destination)
     |
     v
TravelService --> calculate waypoints along road network
     |
     v
Step-by-step travel --> TravelEventRoller generates random events per step
     |                   (encounters, discoveries, weather, obstacles)
     v
JourneyService --> track journey state, progress, active journeys
     |
     v
Arrive at destination --> update entity positions, discover nearby locations
```

## Prerequisites

- Java 21+
- Bun 1.4.5+ (frontend package manager/build runner) and Node.js 24 LTS (Vite development server and test tooling)
- Docker & Docker Compose
- MySQL 8.0+
- Redis 7+
- llama.cpp (optional, has mock mode for development)
- A GGUF format language model (optional)
- Voice playback: NVIDIA GPU with CUDA support; Docker needs NVIDIA Container Toolkit, native development needs `uv` and Git. Ming downloads its weights on first startup ([voice setup](docs/voice-playback.md)).

## Quick Start

### Using Docker Compose (Recommended)

```bash
cp .env.example .env
# Edit .env before startup:
# - Set JWT_SECRET to the output of: openssl rand -base64 48
# - Replace MYSQL_ROOT_PASSWORD, MYSQL_PASSWORD and REDIS_PASSWORD.
# - Keep DATABASE_PASSWORD equal to MYSQL_PASSWORD for native development.
docker compose up -d --build mysql redis backend frontend
```

This starts the core application at http://localhost with mock LLM responses;
no GPU, model download, account with an inference provider, or GitHub secrets are
needed. The API is at http://localhost:8300/graphql. Database and Redis ports bind
to loopback only. Backend logs use the `backend-logs` Docker volume; read them
with `docker compose logs backend`. Keep the default database/user names `chatllm`: the initial
least-privilege grant script uses those names.

For real narration, set `LLM_MOCK_MODE=false`, `LLM_EXTERNAL_SERVER=true`,
`LLM_EXTERNAL_MODEL` and `LLM_EXTERNAL_BASE_URL` in `.env`; add
`LLM_EXTERNAL_API_KEY` if required. A server on the Docker host uses
`http://host.docker.internal:8080`, rather than `localhost` inside the container.
Use the provider's base URL before `/v1/chat/completions`.
When enabling ComfyUI on the Docker host, set `COMFYUI_HOST=host.docker.internal`. Recreate the backend
after configuration changes: `docker compose up -d backend`.

Optional services can be started individually:

```bash
# Voice playback: requires NVIDIA Container Toolkit and a compatible CUDA GPU.
docker compose up -d --build tts
# Monitoring: set GRAFANA_PASSWORD first; both interfaces bind to loopback.
docker compose up -d prometheus grafana
```

Ming downloads and caches its model on first startup, which may take several
minutes. Grafana is at http://localhost:3005 (`GRAFANA_USER` / `GRAFANA_PASSWORD`);
Prometheus is at http://localhost:9091. An unqualified `docker compose up` starts
all default services, including GPU speech and monitoring.

To use the optional containerized llama.cpp server, put a GGUF under `./models/`
as `model.gguf` (or set `MODELS_PATH` and `LLM_MODEL_FILE`). Set
`LLM_EXTERNAL_BASE_URL=http://llama-server:8080` and `LLM_MOCK_MODE=false`, then:

```bash
docker compose --profile llm up -d llama-server backend
```

The bundled llama.cpp configuration defaults to CPU inference (`GPU_LAYERS=0`);
GPU inference needs an explicit GPU reservation in addition to that setting.
Image generation separately requires a reachable ComfyUI service and checkpoint;
set `COMFYUI_ENABLED=true`, its host/port and `COMFYUI_DEFAULT_CHECKPOINT`.

### Native development

Configure `.env` as above and install frontend dependencies with
`cd frontend && bun install --frozen-lockfile`. `./run-dev.sh` loads the root
`.env`, starts Compose MySQL/Redis, native Ming, the backend and Vite at
http://localhost:3000. It stops existing processes on ports 8300 and 3000.
It requires `uv`, Git and a CUDA GPU for Ming; for CPU-only work, start the data
services and backend/frontend manually as below. When using Compose data
services, native ports are MySQL **3307** and Redis **6380**.

### Manual Setup

#### Database

Use these commands only for a **new, empty database**. The canonical schema
contains DROP statements and deletes existing tables; it is not an upgrade script.
```bash
mysql -u root -p
CREATE DATABASE chatllm;
CREATE USER 'chatllm'@'localhost' IDENTIFIED BY 'yourpassword';
GRANT ALL PRIVILEGES ON chatllm.* TO 'chatllm'@'localhost';
FLUSH PRIVILEGES;
```

```bash
mysql -u chatllm -p chatllm < backend/src/main/resources/db/schema.sql
```

#### Backend
```bash
# Export the trusted root configuration into this shell first.
set -a; source .env; set +a
cd backend
./gradlew build -x test -Dquarkus.package.jar.type=uber-jar
java -jar build/chatllm-quarkus-3.0.0-runner.jar
```

Or use Quarkus dev mode (hot reload):
```bash
cd backend
./gradlew quarkusDev
```

#### Frontend
```bash
cd frontend
bun install --frozen-lockfile
bun run dev      # Development (port 3000, proxies to backend :8300)
bun run build    # Production
```

#### LLM Server (Optional)
```bash
./llama-server \
  --model ./models/your-model.gguf \
  --host 0.0.0.0 \
  --port 8080 \
  --ctx-size 4096 \
  --n-gpu-layers 0
```

## Configuration

The backend uses Quarkus configuration via `application.properties` with environment variable overrides. Key environment variables (see `.env.example` for full list):

```env
# Database
DATABASE_HOST=localhost
DATABASE_PORT=3306
DATABASE_NAME=chatllm
DATABASE_USER=chatllm
DATABASE_PASSWORD=yourpassword

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=yourredispassword

# JWT
# Generate your own value with: openssl rand -base64 48
JWT_SECRET=<generated-random-secret>

# LLM
LLM_HOST=localhost
LLM_PORT=8080
LLM_MODEL_PATH=/models/your-model.gguf
LLM_EXTERNAL_SERVER=true        # OpenAI-compatible API
LLM_EXTERNAL_BASE_URL=http://localhost:8080
LLM_EXTERNAL_MODEL=auto
LLM_MOCK_MODE=false             # Set true for development without LLM

# Server (Quarkus)
QUARKUS_HTTP_PORT=8300

# Rate Limiting
RATE_LIMIT_PER_MINUTE=600
```

## Project Structure

[Automated product review](docs/automated-review.md) launches an isolated sandbox,
checks browser journeys, captures screenshots and renders a capability short.

See [repository organization](docs/repository-layout.md) for file ownership,
local-only artifacts and the maintained validation entry points.

```text
backend/
  src/main/java/com/eonmux/chatllm/
    agents/         # Focused judgments, prose generation and pipeline
    map/            # Procedural world/local maps, authoritative stage and travel
    services/       # Conversations, inventory, battles, progress and residents
    messaging/      # Event-bus consumers with lifecycle cleanup
    graphql/        # Code-first resolvers and auth
    llm/            # Inference transport, model pool and prompt templates
  src/main/resources/db/
    schema.sql      # Canonical fresh-install schema
    migrations/     # Bundled idempotent startup upgrades
  src/test/java/    # Unit, service and disposable MySQL integration tests
frontend/
  src/components/   # Chat, roleplay, map and shared UI
  src/pages/        # Application routes
  src/stores/       # Zustand state, speech progress and narrator preference
  src/services/     # Speech playback/planning and progress reconciliation
  src/graphql/      # Typed operations and models
services/tts/       # Ming adapter, voice design, segmentation and unit tests
scripts/            # Native TTS launcher and operational utilities
docs/               # Current guides; dated reports under docs/history/
monitoring/         # Prometheus and Grafana configuration
docker-compose.yml  # App, data services, Ming, monitoring and optional LLM
```

## GraphQL API

SmallRye generates the schema from Java annotations and source resolvers at
startup. Live introspection verified 2026-11-22 exposes **78 query fields,
123 mutation fields and 40 subscription fields**. For copy-ready examples and
input shapes, see [API reference](docs/API.md).

```graphql
mutation Send($input: SendMessageInput!) {
  sendMessage(input: $input) { id content role }
}

subscription Stream($conversationId: String!) {
  messageStream(conversationId: $conversationId) {
    id streamingContent isComplete error characterId messageType battleId
  }
}

mutation Roll($input: RollDiceInput!) {
  rollDice(input: $input) { rollId expression total results }
}
```

## Docker Services

| Service | Port | Profile |
|---------|------|---------|
| MySQL 8.0 | 3307 | default |
| Redis 7 | 6380 | default |
| Backend (Quarkus) | 8300 | default |
| Frontend (Nginx) | `${FRONTEND_PORT:-80}` | default |
| Ming TTS | `127.0.0.1:9797` (browser uses `/tts`) | default |
| llama.cpp (CUDA, optional) | 8080 | `llm` |
| Prometheus | 9091 | default |
| Grafana | 3005 | default |

## Database

The canonical fresh-install schema is
`backend/src/main/resources/db/schema.sql`. Startup applies the bundled,
idempotent upgrades in `db/migrations/` and checks additional columns through
`ApplicationLifecycle`; it does not run Flyway or Liquibase. Keep every upgrade
consistent with the canonical schema. See [database maintenance](docs/DEVELOPER_GUIDE.md#schema-changes).

`./backend/scripts/reset-database.sh` drops and recreates the database. Use it only
for a disposable database or an intentional reset, never as a routine upgrade.

The schema includes seed data: 18+ story templates, a default Assistant character, and 13 achievements with XP values.

## Publication and security

The [sanitization audit](docs/history-publication-audit.md) records the replacement
of private ancestry with one clean initial source commit. No hidden directories
are tracked. CI definitions are [inactive templates](ci/README.md), since
`.github/` is excluded along with other dot-prefixed folders.

See the [public release assessment](docs/public-release.md) for verified checks,
remaining risks and publication steps, and [SECURITY.md](SECURITY.md) for reporting
vulnerabilities. Apache 2.0 covers this repository; model weights and external
services retain their own terms. Public source availability is not a production
security certification.

## Testing

```bash
# Backend
./backend/gradlew -p backend test
# Include the isolated MySQL integration suite (Docker required):
RUN_INVENTORY_DB_TESTS=true ./backend/gradlew -p backend test

# Frontend
cd frontend
bun run test -- --run     # Unit tests (Vitest, one run)
bun run test:coverage     # With coverage
bun run typecheck         # Type checking
bun run lint              # ESLint
bun run format            # Prettier
```

Voice tests (no model download or synthesis needed; pytest and adapter dependencies required):

```bash
PYTHONPATH=services/tts .runtime/tts/venv/bin/python -m pytest services/tts/tests -q
```

With the backend running, validate the browser operations against its live schema:
`cd frontend && node --experimental-strip-types scripts/check-graphql.ts`.

## Security

- JWT authentication with refresh tokens and automatic token refresh
- BCrypt password hashing
- GraphQL auth context with per-resolver authorization checks
- Redis-based per-user rate limiting (sliding window)
- Parameterized SQL (no ORM, no SQL injection)
- Configurable CORS policies
- Session timeout with activity-based extension
- File upload validation (type and size limits)
- WebSocket authentication via `connection_init` Bearer token

## Documentation

- [Documentation index](docs/README.md) — current guides and historical records
- [Voice playback](docs/voice-playback.md) — Ming startup, voice design, segmentation and read-through
- [Maps and movement](docs/world-and-local-maps.md) — local/global views, arrival narration and residents
- [Inventory](docs/inventory.md) and [narrative combat](docs/narrative-combat.md) — permissions and state rules

- [API Reference](docs/API.md) -- GraphQL queries, mutations, subscriptions, and types
- [Developer Guide](docs/DEVELOPER_GUIDE.md) -- onboarding, architecture, and common tasks
- [Feature Parity Report](docs/history/FEATURE_PARITY.md) -- v1.0 (ActiveJ) -> v2.0 (Vert.x) historical migration record
- [Contributing](CONTRIBUTING.md) -- code style, PR process, and testing guidelines
- [Backend README](backend/README.md) -- backend-specific setup and environment variables
- [Scripts README](scripts/README.md) -- utility scripts for ops, testing, and database management

## Version

| Component | Version |
|-----------|---------|
| Java | 21 |
| Quarkus | 3.36 |
| Gradle | (Kotlin DSL via `./gradlew` wrapper) |
| SmallRye GraphQL | (managed by Quarkus BOM) |
| MySQL | 8.0 |
| Redis | 7 |
| React | 18.3 |
| TypeScript | 5.9 |
| Vite | 8 |
| Bun | 1.4.5+ |
| Node.js | 24 LTS |
| Apollo Client | 3.14 |
| Zustand | 4.5 |
| Three.js | 0.182 |
| React Router | 7 |
| Micrometer | (managed by Quarkus BOM) |

**Version**: 3.0.0

## License and copyright

Copyright 2024-2026 Amir Farhad Eslampanah.

Veyloria is licensed under the [Apache License 2.0](LICENSE). Third-party libraries,
models and assets retain their own licenses and copyright notices; Ming's
inference source and downloaded weights are separate from this repository.
