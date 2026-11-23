# Repository organization

## Directory ownership

| Path | What belongs here |
| --- | --- |
| `backend/` | Quarkus application, Gradle wrapper, Java tests and backend-specific launch/database utilities |
| `frontend/` | React application, public assets, colocated Vitest tests, Bun lockfile and frontend tooling |
| `services/tts/` | Python Ming adapter, container definition and speech tests |
| `scripts/` | Maintained operational utilities: health, backup, restore, native speech startup and Docker DB initialization |
| `monitoring/` | Prometheus and Grafana configuration used by Compose |
| `docs/` | Current architecture, API, domain and operational guides |
| `docs/history/` | Dated audits, superseded plans and migration reports; not current setup instructions |
| `ci/github-workflows/` | Inactive CI templates; local commands remain available |

The root contains project policy and orientation (`README.md`, `CONTRIBUTING.md`,
`PRODUCT.md`, `DESIGN.md`, `CHANGELOG.md`, `LICENSE`, `SECURITY.md`), environment
examples, Compose/Docker definitions and installation/deployment entry points.
Source, configuration and documentation files use non-executable permissions;
launch scripts and the Gradle wrapper retain their executable flags.
Root launch scripts retain their established paths so existing commands keep
working. Backend Java packages retain `com.eonmux.chatllm`; database and service
identifiers retain `chatllm` for compatibility.

## Authoritative files

- Database: `backend/src/main/resources/db/schema.sql` for fresh installations,
  registered idempotent upgrades under `db/migrations/`, and guarded additions in
  `ApplicationLifecycle` for existing installations. Resets delete data.
- Runtime configuration and logging: `backend/src/main/resources/application.properties`
  and its environment overrides. Quarkus supplies the GraphQL UI and logging;
  retired standalone Playground/Logback resources are not used. Subscriptions
  use `graphql-transport-ws` on `/graphql`, not the old custom socket protocol.
- GraphQL: annotated Java resolvers and live introspection. Frontend operations
  and types are maintained in `frontend/src/graphql/` and map-specific modules;
  `frontend/scripts/check-graphql.ts` validates them against the running backend.
  `frontend/codegen.yml` is optional tooling; generated output does not replace
  the maintained application types automatically.
- Dependencies: `frontend/package.json` plus `frontend/bun.lock`, backend Gradle
  definitions plus its wrapper, and the pinned native/container Ming setup.
  Keep the wrapper JAR and lockfile in Git: both are required build inputs.
- Design: `DESIGN.md` and source-level interface conventions. Hidden assistant
  design metadata is local-only and excluded from publication.

## Hidden-directory publication policy

No dot-prefixed directory is tracked at any depth, including `.github/`,
`.impeccable/`, `.agents/`, `.codex/` and `.runtime/`. The `.*/` ignore rule and
`python3 scripts/check-public-tree.py` enforce that policy. Standard dotfiles such
as `.gitignore`, `.dockerignore`, `.gitleaks.toml`, `.env.example` and the frontend
formatter config remain versioned because they are files, not hidden folders.
CI templates are kept in visible `ci/`; GitHub Actions is inactive under this
policy. See [CI templates](../ci/README.md).

## Local files and generated output

Keep secrets in ignored environment files; publish configuration keys through
`.env.example` and `frontend/.env.example`. Frontend environment overrides,
personal assistant permissions/preferences, IDE settings and live-tool state
are local files, not shared project configuration.

Do not commit dependency directories, build output, coverage, Python caches,
virtual environments, logs, heap dumps, backups, uploads or downloaded model
weights. Native speech source, models and voice references remain under ignored
`.runtime/tts/`. Do not delete it during routine cleanup: it contains reusable
models and character voice references. Docker volumes and active databases are
also outside a source cleanup.

The ignored `docs/archive/` directory is an existing local archive; versioned
historical records belong in `docs/history/`. Avoid adding generated audit
exports unless they document a specific historical result and are linked from
its report. Removed tools and snapshots remain available in Git history.

## Validation

For the isolated browser/review/fix workflow and animated demo, see
[automated review](automated-review.md). Its scripts live in `scripts/review/`
and browser journeys in `frontend/e2e/`.

Run from the repository root:

```bash
(cd backend && ./gradlew test)
(cd frontend && bun install --frozen-lockfile)
(cd frontend && bun run test -- --run)
(cd frontend && bun run typecheck && bun run lint)
(cd frontend && bun run build)
(cd frontend && bun run check:graphql) # Backend must be listening on :8300
PYTHONPATH=services/tts .runtime/tts/venv/bin/python -m pytest services/tts/tests -q
```

Use `RUN_INVENTORY_DB_TESTS=true ./backend/gradlew -p backend test` for the
inventory/combat integration tests; Docker must be available for their disposable
MySQL databases. The Python command requires the native speech environment.
See [contributing](../CONTRIBUTING.md), [scripts](../scripts/README.md), and
[voice playback](voice-playback.md) for setup and operational details.

Copyright 2024-2026 Amir Farhad Eslampanah. Licensed under [Apache 2.0](../LICENSE).
