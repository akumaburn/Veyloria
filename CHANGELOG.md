# Change log

## Unreleased — development snapshot, 2026-11-22

### Automated review

- Expand the film into a chaptered product overview covering real character/roster
  generation, relationships/alignment, world generation, empires, play, continuity,
  parties, progression, publishing and export. Add a warm romantic presenter,
  embedded chapters and clock-calibrated reuse of completed capture sections.
- Fix data export rejecting the unsupported `includeSystemData` filter; remove
  its nonfunctional checkbox and test selected-section download behavior.

- Capture the adventure film at native 1920-pixel, 100% browser scale; add held
  focus zooms and a closer native 3D travel camera. Give promotional commentary
  an independent lively presenter and support live-model capture deadlines.

- Add a separate real-model adventure capture with audible narrator/character
  playback, an explored local map, actual world travel, item effects and party
  combat votes. Preserve the complete viewport with synchronized speech and
  action/result cuts; document the film script and acceptance gates.

- Refine the capability short with dark-mode captures, stable shots, purposeful
  punch-in/mode cuts, reused Ming narration and encoded-picture stability checks.

- Add a disposable full-stack review runner, Playwright browser journeys,
  accessibility/screenshots/traces, coverage-gap reports and CI evidence uploads.
- Exercise permissions, inventory effects, maps, narrator controls, ordered
  speech playback, travel/arrival/resident greetings and multiplayer combat votes.
- Add optional real-model judgment checks and fresh-reference Ming synthesis;
  render a reproducible animated capability short from actual product captures.
- Restrict unit coverage to application source so generated bundles and tooling
  do not distort the coverage baseline.
- Serialize SSE drain batches and terminal callbacks across Vert.x contexts so
  rapid upstream bursts cannot reorder/corrupt streamed or persisted replies.

### Publication snapshot

- Replace private development ancestry with one initial source commit, using a
  non-deliverable public author email. Keep recovery history outside the repo.
- Exclude every hidden directory from version control, retain standard dotfiles,
  and preserve workflow definitions as inactive templates under visible `ci/`.
- Add a tracked-path publication check and record the final secret/history scan.

### Public development readiness

- Add a dated publication/security assessment, vulnerability reporting policy,
  documentation link checks and scoped secret-scanner fixture exceptions.
- Refresh the frontend lockfile to clear reported advisories; use Vite 8,
  Vitest 4, Bun 1.4.5+ and Node.js 24 LTS. Patch Quarkus to 3.36.3 and document
  remaining Java/Ming runtime advisories without claiming production hardening.
- Fix Compose provider settings, MySQL schema-ready health checks and startup
  privileges, non-root backend log storage and private monitoring/inference ports.
- Validate fresh core installs with a disposable Compose smoke runner and CI job.
  Correct CPU demo, optional GPU/LLM, native environment and destructive SQL docs.
- Make CI work for the current branch and fork PRs without deployment secrets;
  replace unconfigured publishing/SSH/Slack jobs with validation and local artifacts.

### Repository maintenance

- Group dated reports under `docs/history/` and move prompt-template documentation
  into `docs/`; document directory ownership and maintained validation commands.
- Remove retired REST/session/WebSocket debug harnesses, old CSS migration tools
  and their generated report, unused legacy JSON configuration and task schema.
  Remove unused Logback/Playground resources and the retired custom socket protocol.
- Stop tracking personal assistant settings, frontend development overrides and
  live-tool state. Ignore local caches, backups, application data and model weights.
- Normalize file permissions so source, configuration and documentation are not
  executable; retain executable launch scripts and the Gradle wrapper.
- Run frontend CI tests once with coverage instead of entering watch mode.

### Narrative state and multiplayer rules

- Separate LLM judgments into strict single-result encounter, travel, interaction,
  memory, bookkeeping and review calls, with schema validation and bounded retry.
- Await service effects before dependent narration; preserve confirmed arrival
  and interaction blocks through prose/review failures.
- Establish staffed arrival scenes and stable local residents, then save narrator
  arrival before a separate character greeting. Residents stay at their location.
- Publish run-scoped processing status and reconnect snapshots. Unregister pipeline
  consumers on reload and cancel generation tasks as well as their SSE streams.
- Enforce inventory ownership, quantity, variant, effect and gift rules through
  transactions, with durable activity history and multiplayer invalidation.
- Resolve narrative combat with separate adjudication, prose and choices, expected
  round guards, persisted votes, atomic effects/narration, and paused journeys.

### Maps and interface

- Improve 2D map controls and selection, 3D terrain boundaries and camera/marker
  alignment, travel recovery and encounter links to the associated chat.
- Provide local 2D and global 2D/3D map scopes, a direct return to the conversation,
  visited-area fog, loading/error states and responsive layouts.
- Share narrator mode per conversation between chat and map. Hide authoring,
  state overrides, message rewriting, hidden/NPC rolls and AI internals in player
  mode; preserve normal travel, combat, inventory, public rolls and playback.
- Unify page headers, authentication surfaces and shared controls; improve theme,
  typography, responsive wrapping, accessibility and interaction states.
- Align GraphQL documents/types and settings, notification and progression shapes
  with the code-first schema.

### Speech

- Replace voice playback with Ming-3.0-tts-0.5B, with native/container startup,
  automatic model download and persistent caches.
- Design a fixed narrator voice and character voices from demographic fields and
  Personality, prefixing descriptions with `smooth voice,`. Reuse the first
  suitable generated clip as an acoustic reference for later speech.
- Route quoted dialogue and first-person inner voice to the character, including
  malformed orphan quotes around standalone action paragraphs. Spell out acronyms
  and preserve sentence phrasing while buffering unavoidable continuation splits.
- Support narrator block playback and ordered autoplay/read-through with saved
  progress, player-message skipping, one-message lookahead and cancellation.

### Documentation and ownership

- Reconcile setup, pipeline, schema upgrade and API guidance with the current code;
  index current guides separately from dated historical records.
- License the repository under Apache License 2.0 and set the copyright to
  **Copyright 2024-2026 Amir Farhad Eslampanah**.
