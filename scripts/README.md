# Veyloria Scripts

This directory contains utility scripts for managing a Veyloria installation.

## Directory Layout

```
scripts/
├── run-tts.sh                   # Native Ming setup, cached model download and inference
├── health-check.sh              # Composite health check (HTTP, DB, Redis, LLM, disk, logs)
├── backup.sh                    # Timestamped DB + config backup -> backups/
├── restore.sh                   # Interactive restore from backup
├── check-public-tree.py         # Reject hidden directories and private publication paths
├── check-docs.py                # Local Markdown file and heading link validation
├── review/                      # Isolated browser review, fresh Compose smoke and capability short
└── init.sql                     # Docker MySQL charset and application-user grants
```

The authoritative schema-reset utility lives under `backend/scripts/`:

- **`backend/scripts/reset-database.sh`** - Drop and recreate the database using
  `backend/src/main/resources/db/schema.sql`. **Warning**: wipes all data.

## Application Management

- **`health-check.sh`** - Comprehensive component check
  ```bash
  ./scripts/health-check.sh
  ```
  Verifies: backend HTTP + WebSocket, MySQL, Redis, llama.cpp, disk, log freshness.

- **`backup.sh`** - Create a timestamped backup (database dump + config) under `backups/`
  ```bash
  ./scripts/backup.sh
  ```

- **`restore.sh`** - Interactive restore from the available backups
  ```bash
  ./scripts/restore.sh
  ```

## Native voice service

Run `./scripts/run-tts.sh` from the repository root. It requires `uv` and Git,
creates an isolated Python 3.11 environment, pins Ming's inference source,
installs CUDA 12.8 PyTorch and downloads missing model weights on startup.
Subsequent starts reuse `.runtime/tts`; runtime/model/reference files are ignored
by Git. `./run-dev.sh` starts it automatically or reuses a healthy service.
Configuration and cache details are in [voice playback](../docs/voice-playback.md).

## Root-level Operational Scripts

| Script | Purpose |
|--------|---------|
| `run-dev.sh` | Convenience starter for local dev (backend in Quarkus dev mode + frontend + native Ming) |
| `init.sh` | Core Compose startup and smoke checks (configure credentials first) |
| `deploy-prod.sh` | Builds a deployment directory/template; operator setup and hardening required |
| `llama-server-wrapper.sh` | Wrapper that injects Intel OneAPI env before launching `llama-server` |

## Database Scripts

| Path | Purpose |
|------|---------|
| `backend/scripts/reset-database.sh` | Recreate DB from `schema.sql` (destructive reset only) |
| `scripts/init.sql` | Docker entrypoint init script (loaded by docker-compose mysql service) |

`init.sql` configures charset and grants (DML plus CREATE, ALTER, INDEX and
REFERENCES for startup upgrades; no DROP/GRANT OPTION). It assumes database and
application user are both named `chatllm`. Existing volumes retain their old
grants and need a maintenance user to update them. The canonical schema is mounted
separately by Compose. It does not create application tables.

**Canonical schema changes go into `backend/src/main/resources/db/schema.sql`.**
Existing installations receive registered idempotent upgrades and guarded column
additions at backend startup. The reset utility deletes all data; use it only for
intentional resets. See [database maintenance](../docs/DEVELOPER_GUIDE.md#schema-changes).

## Validation

Run `node scripts/review/run.mjs` for the disposable full-stack browser review,
screenshots and animated short. See [automated review](../docs/automated-review.md)
for prerequisites, live-model checks, artifacts and the review/fix workflow.
Use `--demo --live-tts` with `REVIEW_LIVE_LLM_URL` for the separate real-model
complete product overview; its [shot plan](../docs/product-demo-plan.md) requires audible
character playback and visible action/result pairs.

The maintained test suites live beside the components they verify. Run these
from the repository root:

```bash
(cd backend && ./gradlew test)
(cd frontend && bun run test -- --run)
(cd frontend && bun run typecheck)
(cd frontend && bun run check:graphql) # Requires the backend on :8300
RUN_INVENTORY_DB_TESTS=true ./backend/gradlew -p backend test
PYTHONPATH=services/tts .runtime/tts/venv/bin/python -m pytest services/tts/tests -q
```

Database integration tests use disposable MySQL containers, not the active
application database. The Python command requires the native TTS environment.
The old REST/session smoke scripts, obsolete WebSocket harnesses, and CSS tools
for the retired `front/` application have been removed. Their replacements are
the Java/Vitest/Python suites and live GraphQL contract checker above.

## Usage Examples

### Day-to-day

```bash
./run-dev.sh                          # Start dev stack
./scripts/health-check.sh             # Verify health
./scripts/backup.sh                   # Create a backup
```

### Reset the database

```bash
# Stop the backend first (Ctrl-C on quarkusDev, or docker-compose stop backend)
./backend/scripts/reset-database.sh
```

## Environment Variables

Most scripts read from `backend/src/main/resources/application.properties` defaults and the following environment variables (see `.env.example`):

| Variable | Default |
|----------|---------|
| `DATABASE_HOST` | `localhost` |
| `DATABASE_PORT` | `3306` (host-mapped `3307` under docker-compose) |
| `DATABASE_NAME` | `chatllm` |
| `DATABASE_USER` | `chatllm` |
| `DATABASE_PASSWORD` | `chatllm` |
| `JAVA_HOME` | (required to run Gradle) |

## Scheduling

For production, consider cron entries such as:

```cron
# Daily backup at 02:00
0 2 * * * /path/to/Veyloria/scripts/backup.sh

# Health check every 5 minutes
*/5 * * * * /path/to/Veyloria/scripts/health-check.sh >> /var/log/chatllm-health.log
```

## Troubleshooting

### Permission Denied
```bash
chmod +x scripts/*.sh
```

### Database connection failed
Check `DATABASE_*` environment variables and that MySQL is reachable on the configured port (host `3307` under docker-compose, `3306` otherwise).

### Scripts not found
Run scripts from the repo root (not from inside `scripts/`), unless noted otherwise.
