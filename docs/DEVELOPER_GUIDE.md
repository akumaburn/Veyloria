# Veyloria Developer Onboarding Guide

Welcome to the Veyloria development team! This guide will help you get up and running quickly.

## Table of Contents
1. [Project Overview](#project-overview)
2. [Development Environment Setup](#development-environment-setup)
3. [Architecture Overview](#architecture-overview)
4. [Image Generation (ComfyUI)](#image-generation-comfyui)
5. [Getting Started](#getting-started)
6. [Development Workflow](#development-workflow)
7. [Code Style & Standards](#code-style--standards)
8. [Testing Guidelines](#testing-guidelines)
9. [Common Tasks](#common-tasks)
10. [Troubleshooting](#troubleshooting)
11. [Resources](#resources)

## Project Overview

Veyloria is a full-stack, multiplayer fantasy RPG generator with local LLM inference. Built with:

- **Backend**: Java 21, Quarkus 3.36 (CDI + reactive Vert.x core)
- **API**: SmallRye GraphQL (code-first, `@GraphQLApi`) over HTTP + WebSocket
- **Frontend**: React 18 + TypeScript 5 + Apollo Client + Zustand, served via Vite 8
- **Frontend tooling**: Bun 1.4.5+ and Node.js 24 LTS
- **Build (backend)**: Gradle (Kotlin DSL) via `./gradlew` wrapper
- **Database**: MySQL 8.0 (reactive client, no ORM)
- **Cache / Pub-Sub**: Redis 7
- **LLM**: llama.cpp multi-model server pool + optional OpenAI-compatible external API
- **Image Gen**: ComfyUI integration
- **Speech**: Native or container Ming-3.0-tts-0.5B service, same-origin HTTP/WebSocket proxy
- **Observability**: Prometheus + Grafana

### Version

Project version: **3.0.0** (see `backend/build.gradle.kts` and `chatllm.app-version` in `application.properties`).

This is the post-v3.0 stack. Historical migrations:
- v1.0 (ActiveJ) → v2.0 (Vert.x + GraphQL) — see [FEATURE_PARITY.md](history/FEATURE_PARITY.md)
- v2.0 (Vert.x + Maven + npm) → v3.0 (Quarkus + Gradle + Bun) — current

### Key Features

- Real-time chat with LLM token streaming
- Multi-character and multi-user conversations
- Roleplay pipeline with focused, schema-validated judgments and separate generation steps; see [Atomic LLM decisions](#atomic-llm-decisions)
- Procedural world maps with A* road networks, 14 Whittaker biomes, Voronoi cultures
- Journey/travel system with random event rolls
- ComfyUI image generation
- Story templates, community publishing, achievements, RAG knowledge base
- JWT authentication with Redis-backed revocation

## Development Environment Setup

### Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 21+ | SDKMAN: `curl -s "https://get.sdkman.io" \| bash && sdk install java 21.0.5-tem` |
| Node.js | 24 LTS | https://nodejs.org/ |
| Bun | 1.4.5+ | `curl -fsSL https://bun.sh/install \| bash` |
| Docker + Compose | latest | https://docs.docker.com/get-docker/ |
| MySQL client (optional) | 8.0+ | `brew install mysql-client` / `apt-get install mysql-client` |
| Redis CLI (optional) | 7+ | `brew install redis` / `apt-get install redis-tools` |

You do **not** need a system-wide Gradle install — the project ships with the `./gradlew` wrapper.

### IDE Setup

**IntelliJ IDEA (backend)**
- Plugins: `GraphQL`, `Lombok`, `Docker`, `Database Tools`, `Quarkus Tools` (optional)
- Gradle support is bundled; open the project via `File → Open → Veyloria/backend/build.gradle.kts` and import as a Gradle project.

**VS Code (frontend)**
```bash
code --install-extension dbaeumer.vscode-eslint
code --install-extension esbenp.prettier-vscode
code --install-extension bradlc.vscode-tailwindcss
code --install-extension apollographql.vscode-apollo
code --install-extension graphql.vscode-graphql
code --install-extension oven.bun-vscode
```

### Clone and Configure

```bash
git clone <repository-url> Veyloria
cd Veyloria

cp .env.example .env
# Edit .env with your credentials; key variables:
#   DATABASE_PASSWORD, JWT_SECRET (32+ chars), REDIS_PASSWORD,
#   LLM_BINARY_PATH, LLM_MODEL_PATH
```

## Architecture Overview

### System Architecture

```
+-------------------------------------------------------------+
|                         Frontend                            |
|  React 18 + TypeScript + Apollo Client + Zustand + Tailwind |
|  Three.js (global and local maps)             |
|  Runtime: Bun 1.4.5+ Bundler: Vite 8                       |
+----------------------+--------------------------------------+
                       | GraphQL (HTTP + WebSocket)
+----------------------v--------------------------------------+
|                    Backend (Quarkus 3.36)                   |
|                                                             |
|  +---------------+  +--------------+  +----------------+   |
|  | SmallRye      |  |   CDI        |  |  AI Agent      |   |
|  | GraphQL       |<-|  Services    |->|  Pipeline      |   |
|  | (@GraphQLApi) |  | (@Inject)    |  |  (focused agents)    |   |
|  +---------------+  +------+-------+  +-------+--------+   |
|                            |                  |             |
|  +---------------+  +------+-------+  +-------+--------+   |
|  | Reactive      |  | Quarkus     |  | Map Gen /      |   |
|  | MySQL Client  |  | Redis Client|  | LLM / RAG /    |   |
|  | (Mutiny)      |  |             |  | ComfyUI        |   |
|  +-------+-------+  +------+-------+  +-------+--------+   |
+----------+---------------+-----------------+----------------+
           |               |                 |
   +-------v-----+  +------v------+  +-------v------+
   |    MySQL    |  |   Redis     |  |  llama.cpp   |
   |   (:3307)   |  |   (:6380)   |  |   (:8080)    |
   +-------------+  +-------------+  +--------------+
```

### Atomic LLM decisions

A judgment request asks **one question and returns one result**. Supporting data belongs to that result: a travel destination describes one move, and a review reason explains one verdict. A response must not combine independent travel, combat, inventory, relationship and narration decisions. Aggregating validated operations in server code is separate from asking a model to judge several things at once.

All decision calls use `BaseAgent.callJudgment` and `JudgmentProtocol`. The shared boundary requires exactly one complete JSON object. It rejects arrays at the root, multiple objects, trailing prose, duplicate keys, markdown fences, truncated JSON, wrong types, unknown fields and contradictory payloads. Each caller supplies its own exact schema. Optional fields must be omitted when unknown; null is accepted only where that schema explicitly permits it (such as `change:null` and `entry:null`). One schema-repair retry is allowed; a second invalid response is an error. Transport failures also remain errors. Neither becomes a legitimate “nothing happened,” approval, rejection or partially recovered decision. Schema retries generate proposals only and perform no writes.

| Step | One result per request | Handler |
| --- | --- | --- |
| Memory entry | `{"entry":null}` or one fact with its visibility, importance and tags | Validate one entry at a time; aggregate and await cache persistence before RAG promotion. |
| Encounter | `{"encounter":false}` or one established opponent with `encounter:true`, name, description and finite positive HP | Establish the encounter through `BattleService`; stop ordinary movement/effects during active combat. |
| Travel | `{"move":false}`, a false move with one concrete `movementBlockedReason`, or a true move with **exactly one** `currentArea` or `currentLocation` | Validate/apply through `StageService`, then confirm the actual resulting stage. |
| Interaction | `{"occurred":false}` or `{"occurred":true,"result":"one grounded interaction/attempt"}` | Require a result narration; never treat an attempt as proof of payment, loot or success. |
| Inventory bookkeeping | `{"change":null}` or one item operation under `change` | Validate ownership, variant, quantity and item rules in `InventoryService`. |
| Stat bookkeeping | `{"change":null}` or one actor/stat delta under `change` | Apply and await the stat service operation; fixed character attributes cannot be proposed as story stats. |
| Relationship bookkeeping | `{"change":null}` or one directed relationship delta under `change` | Apply and await the relationship service operation. |
| World bookkeeping | `{"change":null}` or one world field/flag operation under `change` | Apply the world-state update. Separately resolved location/scene fields are forbidden. |
| Narration, character or casting review | One strict Boolean `approved` verdict, with optional explanatory metadata; rejection requires a concrete reason | Approval, rejection, disabled review and invalid/unavailable review remain distinct. Never infer a verdict from raw text or repaired JSON. |
| Proposed casting equipment review | One `approved` verdict and reason | Validate equipment plausibility separately; rejection stops the later casting review and prevents automatic approval. |
| Battle adjudication | One chosen attempt's `outcome` and its coherent mechanical consequences | Validate the complete round and commit through the existing battle transaction. No narration or next options in this request. |

The encounter payload cannot carry a move or effect. A false travel/interaction/encounter decision cannot carry the positive branch's payload. A true travel decision cannot contain both area and location, or also claim to be blocked. Invalid responses are retried or fail, never interpreted field by field until a plausible answer is found.

#### Ordinary round order

After the final scheduled character response, the pipeline loads the actual author of the saved player message, not an assumed conversation host. Highlighter/RAG prepare context. Each memory extraction call returns one entry or an explicit terminator, with a limit of eight entries; the current round is selected from the latest player message. Entry arrays are assembled in server code and the cache write finishes before RAG promotion. A separate encounter check precedes travel. Without active combat, travel is decided and applied first; only when there is no travel or blocked travel does the independent interaction check run. No model request bundles those questions.

The Accountant then processes inventory, stats, relationships and world state **sequentially**. Each request proposes just one operation or `change:null`. Accepted proposals are included in the next request to prevent repetition. Server code stages and assembles the individual results; an invalid response, repeated operation identity, conflicting world-field update or exhausted budget fails the collection rather than silently accepting a partial batch. The limit is eight accepted operations per domain, or 24 inventory operations during initialization, followed by an explicit no-change terminator. Initialization requires at least one grounded starting item. These bounds prevent an endless model loop; more focused calls can take longer than the former combined request.

Once collection succeeds, service validation/application is awaited before dependent narration. Inventory operations, stat updates and relationship updates are not detached background writes. Current inventory, stats and relationships are refreshed for narration. Application failures are recorded as warnings instead of fabricated successful effects. Paused/superseded rounds are checked before subsequent state operations and before final message persistence.

Narration is **generation**, not another “should I narrate?” judgment. If no opening/event/result requires a block, server code skips the model call. Otherwise the writer receives the resolved event and produces prose. Editorial review returns one verdict about that prose; an empty rewrite, control-tag-only output or unavailable reviewer cannot erase an already confirmed arrival/interaction. The required text is saved as an idempotent narrator message before the player's turn reopens.

#### Progress and arrival residents

The `AgentPipelineBridge` owns exactly one `roleplay.pipeline.process` consumer and unregisters it synchronously on `ShutdownEvent`, including development reloads. Vert.x survives those reloads: an unregistered old bridge would keep receiving round-robin requests with obsolete pipeline code. Lifecycle regression tests reuse the same Vert.x instance to verify that shutdown leaves no handler and the next registration owns every request. If updating a development process that already leaked handlers, restart the backend once to remove the old registrations; reloading only the corrected class cannot clean up earlier instances.

`ConversationProgressService` publishes the actual running phase over `groupStateChanged` (`processingOnly:true`, `processingStatus`) and exposes the current snapshot on `Conversation.processingStatus` for reconnects. The phase includes the atomic encounter/travel/interaction checks, each bookkeeping domain, map update, narration/revision, save and greeting. Processing frames clear the displayed speaker without pretending to change the persisted turn state. Each run has an ID and monotonically increasing event sequence; old-run updates/finishes cannot replace the current phase. Cancellation and completion clear the run. Clients keep terminal frames to reject delayed snapshots and use ordinary state/turn events to resume character status.

After a confirmed local move, `LocationResidentService` establishes the destination scene and its local cast before narration. Staffed places have deterministic, conversation-and-area-bound residents: an innkeeper, merchant, blacksmith or gate guard; area metadata may specify `residentRole`, and `populated:true` provides a local resident when no staff role matches. Explicit `populated:false`, `abandoned:true` and abandoned/deserted descriptions prevent automatic residents. This is a server rule, not another optional LLM encounter judgment. Hostile encounters still use the separate encounter decision.

Resident identity, membership and scene activation commit in one transaction after verifying the actual area. Repeat visits reuse the profile. Metadata anchors the resident to that room; ordinary stage moves and global travel exclude residents from the traveling party, and departing residents become inactive/offstage. No model-created equipment or transaction is implied by a greeting.

The narrator receives the resolved move and present resident, then writes the travel/arrival block. That block is saved **before** a separate `ResidentGreetingAgent` prose call. The resulting greeting is a normal character dialogue message, with a stable event key and an embedded voice profile for immediate readout. A generation failure uses the resident's saved greeting. Persistence rechecks the area, turn state and both saved player/assistant round boundaries; superseded work cannot append the greeting. The player turn opens only after this sequence; greeting generation does not start another party round. Fast travel and completed waypoint journeys use the same narration-before-greeting order.

#### Combat and casting boundaries

Combat uses three sequential requests: adjudicate the chosen attempt, write prose from that fixed adjudication, then generate an options artifact only if the result is `ONGOING`. A terminal outcome skips the options call. The sole outcome enum is `ONGOING`, `VICTORY`, `DEFEAT` or `RETREAT`; server code derives the existing service-facing `battleEnded` and `endStatus` fields. Zero enemy HP and victory must agree. Opening adjudications cannot alter health, consume items, grant loot or terminate the encounter.

Later narration/options calls cannot submit another mechanical result. Their validated fields are assembled with a copy of the fixed adjudication, not merged over its state. Invalid options fail before round commit. Unavailable/empty combat prose can fall back to the adjudicated factual consequence. The existing battle transaction still commits the round, effects and saved narration together, with optimistic round checks and publication after commit.

Casting creates one character proposal artifact, reviews its starting equipment separately, and only then requests the casting verdict when equipment passed. A malformed/unavailable review cannot approve a proposal. Reviews do not create inventory or emit unrelated bookkeeping changes.

#### Generation artifacts and unsupported decisions

Character text, narrator prose, a character proposal, a map and battle choices are generation artifacts, not permission to execute independent actions. They may contain multiple descriptive fields or candidate options; service code does not interpret those fields as additional judgments. Highlighter memory classification uses the same strict single-result boundary, including finite importance and known visibility IDs. Rolled journey encounters also use a separate hostility judgment after prose generation; continuation and arrival come from server waypoints, and embedded control tags cannot start combat. The Muse now generates one optional suggestion, without separately scoring tension or choosing pacing. Summaries and suggestions cannot authorize travel, combat or inventory changes.

World-map generation and initial positioning remain in the existing initialization workflow, before these ordinary-round decisions. The old competing Accountant map-generation branch was removed. The unconsumed `roleplay.achievement.triggered` dispatch was also removed: the model is not asked to invent achievement IDs or return an award decision with no handler. Explicit achievement service/API operations remain separate.

“One atomic judgment” does **not** mean the whole narrative round is one distributed database transaction. Travel and ordinary item/stat/relationship operations retain their own service boundaries; cancellation does not undo effects already committed. Battle rounds retain their stronger transaction. Validation/retries are side-effect free, and later narrative failures must preserve truthful confirmed events.

#### Extending and testing the contract

Add a focused schema and explicit result handler before introducing a judgment prompt. Use `callJudgment`; do not use the tolerant generation parser for decisions, default missing Booleans, infer approval from text, accept several roots or repair a truncated verdict into authority. Keep presentation requests unable to overwrite resolved fields. Await state effects before dependent prompts. Use separate requests when two results would need independent handlers.

Validated substeps are recorded under `encounter_decision`, `travel_decision`, `interaction_decision`, `accountant_inventory`, `accountant_stats`, `accountant_relationships`, `accountant_world` and `battle_adjudication`; the overall agent run records collection failures. Substeps retain the existing parent agent/default model selection.

Run `RUN_INVENTORY_DB_TESTS=true ./backend/gradlew -p backend test --console=plain`. Contract regressions cover multiple/duplicate/truncated outputs, contradictory branches, bounded retries, one-operation bookkeeping and duplicate identities, ordered/awaited writes, fixed combat results, skipped optional generation, preserved arrival narration and real MySQL narrator-message persistence.

### Backend Structure

```
backend/
├── build.gradle.kts                    # Quarkus Gradle plugin
├── settings.gradle.kts
├── gradle.properties                   # quarkusPlatformVersion=3.36.3
├── gradlew / gradle/                   # Gradle wrapper
├── scripts/
│   └── reset-database.sh               # Apply schema.sql
└── src/main/
    ├── java/com/eonmux/chatllm/
    │   ├── lifecycle/                  # ApplicationLifecycle (StartupEvent/ShutdownEvent)
    │   ├── config/                     # AppConfig (@ConfigProperty), RoleplayEventAddresses
    │   ├── agents/                     # Judgment contracts, focused agents, generation and pipeline orchestration
    │   ├── map/                        # procedural map + local interiors + journey/travel engine
    │   │   ├── generation/             # Terrain, Roads, Locations, SimplexNoise, Cultures
    │   │   ├── models/                 # Biome, TerrainData, JourneyState, ...
    │   │   └── *Service.java           # MapService, LocalMapService, StageService, JourneyService, TravelService
    │   ├── models/                     # Domain models and DTOs (domain + DTO + response)
    │   ├── services/                   # CDI domain services
    │   │   └── auth/                   # JWTAuthProvider, AuthContext
    │   ├── graphql/
    │   │   ├── auth/                   # GraphQLAuthContext (@RequestScoped)
    │   │   ├── resolvers/              # Code-first resolvers (@Query / @Mutation / @Subscription)
    │   │   └── scalars/                # CustomScalars, JsonObjectScalarAdapter
    │   ├── llm/                        # llama.cpp integration + GGUF parser
    │   ├── comfyui/                    # Image generation
    │   ├── messaging/                  # Event-bus bridges with shutdown cleanup
    │   └── utils/                      # Cache, validation, Base64, service helpers
    └── resources/
        ├── application.properties      # Quarkus + chatllm.* configuration
        └── db/schema.sql               # Canonical fresh-install schema
```

The GraphQL schema is **code-first** — generated at runtime from annotated classes. No GraphQL schema snapshot is checked in; inspect the authoritative schema via the dev GraphQL UI or `__schema` introspection. The live root surface verified 2026-11-22 is **78 queries, 123 mutations and 40 subscriptions**. These are root-field counts, distinct from the 221 frontend operation documents and 36 fragments validated by the contract checker.

### Frontend Structure

```
frontend/
├── package.json                        # "engines": { "bun": ">=1.0.0" }
├── bun.lock                            # Bun lockfile (no package-lock.json)
├── vite.config.ts                      # Dev server :3000, proxies /graphql to :8300
├── tsconfig.json                       # Strict mode, path aliases (@components, @stores, ...)
├── tailwind.config.js
└── src/
    ├── App.tsx
    ├── main.tsx
    ├── components/                     # Chat, roleplay, maps and shared UI
    │   ├── chat/                       # Chat and voice controls
    │   ├── roleplay/                   # Roleplay tools and overlays
    │   ├── map/                        # 2D canvas and 3D ray-marched maps
    │   ├── ui/                         # Shared Radix-based primitives
    │   ├── layout/                     # Header, Sidebar, MainLayout
    │   ├── settings/                   # Settings panels
    │   ├── auth/                       # LoginForm, RegisterForm
    │   ├── admin/                      # AdminDashboard
    │   ├── shared/                     # CharacterForm
    │   └── conversation/               # ConversationList
    ├── pages/                          # Lazy-loaded application routes
    ├── stores/                         # Zustand stores (auth, character, conversation, ...)
    ├── hooks/                          # 5 hooks: useAuthMutation, useFormState, useGraphQL, useModalState, useRoleplay
    ├── graphql/
    │   ├── operations.ts
    │   ├── roleplay-operations.ts
    │   ├── types.ts
    │   ├── utils.ts
    │   └── index.ts
    └── lib/
        ├── apollo-client.ts            # Apollo + automatic token refresh
        ├── websocket-client.ts         # graphql-transport-ws client (subscription dedup, reconnect)
        ├── storeHelpers.ts             # setLoading / setError / clearLoadingAndError / handleStoreError
        ├── storageHelper.ts
        ├── debug-mode.ts
        └── utils.ts
```

## Image Generation (ComfyUI)

Image generation runs against an external ComfyUI server, configured under
`chatllm.comfyui.*` (`COMFYUI_ENABLED=true`, `COMFYUI_HOST`, `COMFYUI_PORT`,
`COMFYUI_MAX_QUEUE_SIZE`, `COMFYUI_DEFAULT_STEPS`, `COMFYUI_DEFAULT_CHECKPOINT`).

Pipeline, end to end:

1. **Template** — `comfyui_workflow` rows (built-ins: `character_portrait`
   1024×1024, `scene_change` 1344×768) supply the workflow JSON, model, and
   default parameters. A `generateImage` input without width/height falls back
   to the template's `defaultParams`.
2. **Prompt enhancement** — `PromptEnhancer` appends the character's identity
   fragment (name, age, description) after the trigger prefix so per-character
   sheets keep a stable likeness.
3. **Queue** — `ComfyUIQueueManager` holds at most `max-queue-size` (default 5)
   jobs and submits one concurrently to ComfyUI. It polls per-prompt progress
   with a timeout guard, then publishes `comfyui.job.complete` on the Vert.x
   event bus (`jobId`, `conversationId`, `resultUrl`, `seed`). Queue-full
   rejections are visible: the `generated_image` row is marked `failed` with a
   reason instead of sitting `pending`.
4. **Completion** — the `comfyui.job.complete` consumer marks the row
   `complete`, stores the peer's `/view` URL in `filePath`, and emits
   `IMAGE_GENERATED` per conversation.
5. **References** — `SheetRefService` derives 8 active `character_visual_ref`
   crops from a completed sheet. The row must carry a `characterId`
   (pass it in the `generateImage` input) or no refs are derived.
6. **Avatar** — when a 1024×1024 sheet with a `characterId` completes, the
   character's `avatar` is pointed at the new `/view` URL, so every avatar
   surface (character card, participant list, message list) picks the portrait
   up automatically.

GraphQL surface: `generateImage(input)` (accepts `triggerType`/`workflowId` for
the built-in workflow templates), `generatedImages(conversationId, characterId?)`,
`comfyuiStatus` (queue size plus the peer's checkpoints/LoRAs via
`/object_info`), `comfyuiWorkflows`. `GeneratedImage.status` is exposed from the
row's metadata (`queued`/`complete`/`failed`).

UI surfaces: the character card shows the latest portrait and a
*Generate/Regenerate* action (`triggerType: character_portrait`, scoped to the
story the card was opened from); the roleplay tool panel's **Images** tab offers
*Portrait* (per character) and *Scene* (`triggerType: scene_change`) quick
actions plus a free-form prompt form; the **Settings → ComfyUI** tab shows live
server status and the model/LoRA inventory.

Operating notes:

- `filePath` stores the peer's `/view` URL; the browser renders it directly.
- The peer's prompt cache can ignore both input values and graph topology: a
  render that "completes" in seconds and re-serves an old file is a cache hit.
  Only a peer-side ComfyUI restart (cold cache) clears it; no client-side
  workflow change busts it.

## Getting Started

### Quick Start with Docker Compose

Follow the [root quick start](../README.md#quick-start) to generate a JWT secret
and replace database/cache passwords before these commands. The example uses
mock inference; real LLMs, voice playback and monitoring require the separate
configuration documented there. Node.js 24 LTS is required alongside Bun.

```bash
docker compose up -d --build mysql redis backend frontend # core; configure .env first
docker compose --profile llm up -d llama-server backend # optional configured inference

curl http://localhost:8300/health/live # Verify backend
open http://localhost                  # Frontend (Nginx on :80)
```

### Manual Development Setup

**Backend**

```bash
cd backend

# One-time: apply the schema
./scripts/reset-database.sh

# Hot-reload dev mode
./gradlew quarkusDev

# Remote debugger on :5005 (suspend until attached)
./gradlew quarkusDev -Ddebug=5005 -Dsuspend=true

# Open GraphQL UI (requires ENABLE_GRAPHQL_PLAYGROUND=true)
open http://localhost:8300/q/graphql-ui/
```

**Frontend**

```bash
cd frontend
bun install
bun run dev                            # http://localhost:3000 (proxies /graphql to :8300)
bun run build                          # typecheck + production build
bun run preview                        # Serve the production build locally
```

**Voice playback**

`./run-dev.sh` starts the native Ming service alongside frontend/backend. For
manual startup use `./scripts/run-tts.sh` from the repository root. It requires
`uv`, Git and a supported CUDA GPU; model/source/dependencies are cached under
`.runtime/tts`. Docker Compose includes a GPU-backed TTS service. See
[voice playback](voice-playback.md) for download, proxy, caching and test details.
Native Python source changes require restarting that service (no auto-reloader).

**LLM Server (optional)**

```bash
./llama-server \
  --model ./models/your-model.gguf \
  --host 0.0.0.0 \
  --port 8080 \
  --ctx-size 4096
```

Set `LLM_MOCK_MODE=true` to skip the LLM entirely for UI/API work.

## Development Workflow

### Feature Branch

```bash
git checkout -b feature/your-feature

# Backend
cd backend && ./gradlew quarkusDev

# Frontend (in another shell)
cd frontend && bun run dev

# Before pushing
cd backend  && ./gradlew build -x test
cd frontend && bun run typecheck && bun run lint && bun run test
```

### Conventional Commits

- `feat:` new feature
- `fix:` bug fix
- `refactor:` non-behavioral code change
- `docs:` documentation only
- `test:` adding/changing tests
- `chore:` build/tooling
- `perf:` performance improvement
- `ci:` CI configuration

## Code Style & Standards

### Backend (Java + Quarkus + Mutiny)

```java
// CDI services are @ApplicationScoped, dependencies via @Inject — no constructors required
@ApplicationScoped
public class UserService {

    @Inject
    MySQLPool pool;            // Quarkus Reactive MySQL Client

    @Inject
    JWTAuthProvider auth;

    /** Reactive read via Mutiny Uni<T>. Never block. */
    public Uni<User> findById(String id) {
        return pool.preparedQuery("SELECT id, email, display_name FROM users WHERE id = ?")
                   .execute(Tuple.of(id))
                   .onItem().transform(rs -> rs.iterator().hasNext()
                       ? mapRowToUser(rs.iterator().next())
                       : null);
    }

    /** Chain reactive steps; handle errors with onFailure(). */
    public Uni<User> register(RegisterInput input) {
        return validate(input)
            .onItem().transformToUni(this::checkDuplicate)
            .onItem().transformToUni(this::hashPassword)
            .onItem().transformToUni(this::insertRow)
            .onFailure().invoke(err -> Log.errorf(err, "registration failed"));
    }
}

// GraphQL resolvers use @GraphQLApi + @Query / @Mutation / @Subscription
@GraphQLApi
public class UserResolver {

    @Inject UserService users;
    @Inject GraphQLAuthContext auth;

    @Query("me")
    public Uni<User> me() {
        return auth.requireAuth()
                   .onItem().transformToUni(ctx -> users.findById(ctx.userId()));
    }
}
```

**Rules**
- Services: `@ApplicationScoped` beans; never manually instantiate
- No blocking calls in reactive paths — always return `Uni<T>` / `Multi<T>`
- Parameterized SQL only; never concatenate user input
- JWT extraction happens in `GraphQLAuthContext` (`@RequestScoped`); resolvers call `requireAuth()` / `requireAdmin()`
- WebSocket auth comes from `connection_init.payload.authorization` — see [DEVELOPER_GUIDE.md Troubleshooting](#troubleshooting)

### Frontend (TypeScript + React + Zustand)

```typescript
// Functional components + typed props
interface UserProfileProps {
  userId: string;
  onUpdate?: (user: User) => void;
}

export const UserProfile: React.FC<UserProfileProps> = ({ userId, onUpdate }) => {
  const { data, loading, error } = useQuery(GET_USER, { variables: { id: userId } });

  if (loading) return <LoadingSpinner />;
  if (error)   return <ErrorMessage error={error} />;

  return <div className="user-profile">{data?.user.displayName}</div>;
};

// Zustand stores use Immer + devtools + shared storeHelpers
import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { setLoading, setError, clearLoadingAndError, handleStoreError } from '@/lib/storeHelpers';

export const useCharacterStore = create<CharacterState>()(
  immer((set, get) => ({
    characters: [],
    loading: false,
    error: null,

    async fetchCharacters() {
      setLoading(set, true);
      try {
        const data = await client.query({ query: LIST_CHARACTERS });
        set(draft => { draft.characters = data.characters; });
        clearLoadingAndError(set);
      } catch (e) {
        handleStoreError(set, e);
      }
    },
  }))
);
```

**Rules**
- Immutable updates only — mutate via Immer drafts, never in-place
- Use `@/lib/storeHelpers` for standard loading/error transitions
- Roleplay GraphQL operations live in `@/graphql/roleplay-operations`
- Types live in `@/graphql/types` — do not duplicate
- The single path alias `@/` maps to `frontend/src/` in TypeScript, Vite and Vitest.
- WebSocket client (`@/lib/websocket-client`) owns subscription dedup — do not create parallel WS connections

## Testing Guidelines

### Backend

Tests use JUnit/Mockito and, where needed, disposable MySQL through
Testcontainers. Place tests under `backend/src/test/java/` and use existing
executable examples:

- [SSEStreamOrderingTest](../backend/src/test/java/com/eonmux/chatllm/services/SSEStreamOrderingTest.java)
  checks ordering across concurrent stream drains.
- [InventoryServiceDatabaseTest](../backend/src/test/java/com/eonmux/chatllm/services/InventoryServiceDatabaseTest.java)
  checks transactional item effects, ownership and concurrent consumption against
  isolated MySQL. Set `RUN_INVENTORY_DB_TESTS=true` and make Docker available;
  otherwise this class is skipped.

### Frontend

Vitest and Testing Library are configured in `vitest.config.ts` with shared
setup in `src/test/setup.ts`. Colocate tests with the component or service.
[DataExportImport.test.tsx](../frontend/src/components/DataExportImport.test.tsx)
is an executable example using Apollo `MockedProvider`, supported GraphQL input
fields and browser download stubs. Match operation variables and response shapes
to the current API, rather than inventing alternate mock schemas.

### Running Tests

From the repository root, include disposable MySQL integration tests with
`RUN_INVENTORY_DB_TESTS=true ./backend/gradlew -p backend test`. These tests use
Testcontainers and do not reset the development database. Voice tests use
`PYTHONPATH=services/tts .runtime/tts/venv/bin/python -m pytest services/tts/tests -q`.
With a running backend, validate the frontend GraphQL contract using
`cd frontend && node --experimental-strip-types scripts/check-graphql.ts`.


```bash
# Backend
cd backend
./gradlew test
./gradlew test --tests '*SSEStreamOrderingTest'

# Frontend
cd frontend
bun run test -- --run
bun run test:coverage
bun run test:ui           # Vitest UI
```

## Common Tasks

### Add a GraphQL operation

1. **Backend**: add the method to a `@GraphQLApi` resolver class:
   ```java
   @GraphQLApi
   public class CharacterResolver {
       @Query("characterCategories")
       public Uni<List<String>> characterCategories() {
           return characters.listCategories();
       }
   }
   ```
2. **Frontend**: add the query to `src/graphql/operations.ts` (or `roleplay-operations.ts` for roleplay features) and consume via Apollo:
   ```typescript
   export const CHARACTER_CATEGORIES = gql`
     query CharacterCategories { characterCategories }
   `;
   ```

The backend regenerates the GraphQL schema on startup — no manual schema file to edit.

### Add a Zustand store

1. Create `src/stores/myStore.ts`; use immutable Zustand updates, with Immer/storeHelpers for stores that need those helpers.
2. Register it alongside the other stores; they are each self-contained (no global root store).
3. Use from components via the returned hook (`useMyStore(state => state.value)`).

### Schema changes

`backend/src/main/resources/db/schema.sql` is authoritative for a fresh install.
`ApplicationLifecycle.ensureRelationshipSchema` also applies bundled idempotent
resources in `db/migrations/` (affinity, alignment, groups, local maps, inventory
activity and journey encounter status) and guards additive column changes using
`information_schema`. There is no Flyway/Liquibase runner or version ledger.

When evolving the schema, update the canonical SQL and provide a repeatable,
non-destructive upgrade registered in that startup path. Verify fresh installs
and upgrades independently. Startup upgrades do not repair arbitrary legacy
schema drift; compare and plan those deployments explicitly. Back up a populated
database before upgrading it. `reset-database.sh` drops and recreates the database;
use it only for disposable data or an explicitly intended reset.

### Debugging

**Backend**

```bash
# Enable verbose logs
export LOG_LEVEL=DEBUG
./gradlew quarkusDev

# Remote JVM debug (attach on :5005)
./gradlew quarkusDev -Ddebug=5005 -Dsuspend=true

# Quarkus dev UI
open http://localhost:8300/q/dev/
```

**Frontend**

- Chrome / Firefox React DevTools
- Apollo Client DevTools browser extension
- `import { enableDebugMode } from '@/lib/debug-mode'` exposes extra logging
- Network tab → filter by `graphql` to inspect HTTP queries; `WS` tab for subscriptions

## Troubleshooting

### Backend

| Symptom | Fix |
|---------|-----|
| `Connection refused` on MySQL | `docker compose ps mysql` ; note host port is `3307` |
| `JWT token invalid` | Same `JWT_SECRET` in backend env + running process; at least 32 chars |
| `SmallRye error: FieldUndefined` or `SubselectionRequired` | Likely a serialization gotcha — check that the type isn't a raw `Map` and no needed field is `transient`. Expose supported fields through explicit source resolvers when needed; inspect live introspection and run the client contract checker. |
| WebSocket auth silently fails | Token must be in `connection_init.payload.authorization`, not an HTTP header. Browser `WebSocket` cannot send custom headers. |
| Event-bus subscriber never fires | Consumers for LLM and Redis events are in `messaging.LLMBridge` / `RedisCacheBridge`; verify those CDI bridges are not excluded by build profiles. |

### Frontend

| Symptom | Fix |
|---------|-----|
| `Module not found` | `bun install --frozen-lockfile`; check aliases and dependency declarations before changing the lockfile |
| GraphQL network error | `curl http://localhost:8300/health/live`; check `VITE_API_URL` / `VITE_WS_URL` and Vite proxy in `vite.config.ts` |
| Type errors after schema change | Run `bun run check:graphql` against the backend; update the maintained operations/types, then `bun run typecheck` |
| Subscription not firing | Confirm a single WebSocket connection in DevTools → Network → WS; check the current auth store and the connection-init Bearer token |

### Performance

**Backend**
- Prometheus at `:9091`, Grafana at `:3005` (`GRAFANA_USER` / `GRAFANA_PASSWORD`) under docker-compose
- Heap dump: `jcmd <pid> GC.heap_dump /tmp/heap.hprof`
- Thread dump: `jcmd <pid> Thread.print`

**Frontend**
- `bun run build` + inspect chunk sizes
- React Profiler + `React.memo` for expensive subtrees
- Virtualize long lists with `@tanstack/react-virtual`

## Resources

### Internal
- [API Reference](./API.md)
- [Feature Parity Report](history/FEATURE_PARITY.md) — v1.0 → v2.0 historical record
- [Contributing](../CONTRIBUTING.md)
- [Backend README](../backend/README.md)
- [Scripts README](../scripts/README.md)
- [Contributing](../CONTRIBUTING.md) — development conventions and review workflow

### External
- [Quarkus Guides](https://quarkus.io/guides/)
- [SmallRye GraphQL](https://smallrye.io/smallrye-graphql/)
- [Mutiny Guide](https://smallrye.io/smallrye-mutiny/latest/)
- [Apollo Client](https://www.apollographql.com/docs/react/)
- [Zustand](https://zustand.docs.pmnd.rs/)
- [Bun Docs](https://bun.sh/docs)
- [Vite Docs](https://vitejs.dev/)
- [GraphQL Spec](https://spec.graphql.org/)

### Tools & Endpoints

| Resource | URL |
|----------|-----|
| Frontend (Vite dev) | http://localhost:3000 |
| Frontend (Nginx in Docker) | http://localhost |
| GraphQL HTTP | http://localhost:8300/graphql |
| GraphQL UI (dev, toggle) | http://localhost:8300/q/graphql-ui/ |
| Quarkus Dev UI | http://localhost:8300/q/dev/ |
| Health | http://localhost:8300/health |
| Metrics | http://localhost:8300/metrics |
| Prometheus (Docker) | http://localhost:9091 |
| Grafana (Docker) | http://localhost:3005 |

### Cheatsheet

```bash
# Backend
cd backend
./gradlew quarkusDev           # Hot-reload dev
./gradlew build -x test        # Uber-jar
./gradlew test                 # Tests
./gradlew dependencies         # Dep tree

# Frontend
cd frontend
bun install
bun run dev                    # Dev server
bun run build                  # Production build
bun run test                   # Vitest
bun run lint                   # ESLint
bun run typecheck              # tsc --noEmit
bun run format                 # Prettier

# Docker
docker compose up -d
docker compose --profile llm up -d
docker compose logs -f backend
docker compose down

# Database
mysql -h localhost -P 3307 -u chatllm -p chatllm
./backend/scripts/reset-database.sh

# Redis
redis-cli -p 6380
```

Happy coding!

### Narrator mode in the frontend

Narrator mode is an explicit per-conversation authoring preference in
`frontend/src/stores/narratorModeStore.ts`, shared by chat and the standalone map.
It defaults off after a page reload and does not carry into other conversations.
Both views offer a toggle. This is a UI preference; existing server permissions
continue to authorize mutations independently.

Player mode keeps normal exploration/travel, public dice rolls, combat actions,
inventory use/equip/give, bookmarks, images, playback, and read-only world and
character sheets. Narrator mode additionally exposes manual world/scene, stat,
relationship and inventory authoring; battle overrides; global map generation,
editing and location deletion; hidden/NPC dice rolls; story branching; and AI
insights, memory entries and character proposals. Roleplay message editing,
deletion, response regeneration and model reasoning are also hidden in player
mode, since rewriting resolved turns can contradict recorded game state.
Ordinary chats retain their existing message controls. Turning narrator mode off
also removes open authoring surfaces rather than only hiding their launchers.
