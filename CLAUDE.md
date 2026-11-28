# Claude Code Rules for Veyloria

## Database Schema Rules

The canonical fresh-install schema is `backend/src/main/resources/db/schema.sql`.
Keep it synchronized with any schema change. Existing installations receive
bundled idempotent SQL resources under `db/migrations/` and guarded column
additions through `ApplicationLifecycle.ensureRelationshipSchema` at startup.
There is no Flyway/Liquibase runner or version ledger.

Update both fresh-install SQL and the repeatable upgrade path when required,
and verify them separately. Do not reset a populated database to apply an
ordinary schema upgrade. `./backend/scripts/reset-database.sh` drops and recreates
the database and is appropriate only for intentional resets/disposable data.
See [database maintenance](docs/DEVELOPER_GUIDE.md#schema-changes).

Read README.md for project information.

## Key Conventions

- **No ORM**: Direct parameterized SQL queries via Quarkus Reactive MySQL Client (Mutiny)
- **CDI injection**: Services are `@ApplicationScoped` beans injected via `@Inject`
- **Reactive/async**: All backend operations use Mutiny `Uni<T>` / `Multi<T>`, never blocking calls
- **GraphQL-first**: Application data uses SmallRye GraphQL (`@GraphQLApi`, code-first); Ming speech uses the separate `/tts` HTTP/WebSocket proxy
- **Single schema file**: Database shape lives in `schema.sql`; bundled startup upgrades must match it
- **Config via application.properties**: Quarkus `@ConfigProperty` with env var overrides (`chatllm.*` namespace)
- **Event bus available**: Vert.x event bus accessible via CDI-injected `io.vertx.mutiny.core.Vertx` for pub/sub
- **Immutable state**: Frontend stores use Zustand + Immer for immutable updates
- **Store helpers**: Common loading/error patterns via `@lib/storeHelpers` (`setLoading`, `setError`, `clearLoadingAndError`, `handleStoreError`)
- **Centralized roleplay GraphQL**: Roleplay operations in `@graphql/roleplay-operations`
- **Frontend tooling**: Bun package management; Vite/Vitest/TypeScript also support the documented Node runners

## Docker Services

| Service | Port | Profile |
|---------|------|---------|
| MySQL 8.0 | 3307:3306 | default |
| Redis 7 | 6380:6379 | default |
| Backend (Quarkus) | 8300:8300 | default |
| Frontend (Nginx) | `${FRONTEND_PORT:-80}`:80 | default |
| Ming TTS | 127.0.0.1:9797 | default (NVIDIA GPU) |
| llama.cpp server | 8080:8080 | `llm` |
| Prometheus | 9091:9090 | default |
| Grafana | 3005:3000 | default |

## Dev Stack (local, non-Docker)

- Backend: `cd backend && LLM_EXTERNAL_BASE_URL=http://192.168.0.28:8000 ./gradlew quarkusDev` (:8300)
- Frontend: `cd frontend && bun run dev` (:3000)
- `LLM_EXTERNAL_BASE_URL` is required: without it the app defaults to
  `http://localhost:8080`, so both the LLM bridge and the `/health` LLM
  readiness probe silently target an empty port (`llm: "down"` -> 503).
- The machine sleep SIGTERMs background dev jobs every few days. Restart both,
  then poll `curl http://localhost:8300/health` until 200. If Vite reports
  "Port 3000 is in use", an orphaned Vite child from the previous job survived;
  kill it before restarting the frontend.
