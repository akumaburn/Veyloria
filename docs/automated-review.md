# Automated testing and review loop

## Outcome and scope

`node scripts/review/run.mjs` packages the application, creates disposable data
services, seeds a fictional adventure, launches a separate backend and frontend,
runs browser journeys, captures evidence and renders a short capability
demo. It tears down its own processes and containers in a `finally` block.
It never loads the root `.env` into Compose and never connects to the existing
application database, Redis, conversations or speech-reference directory.

Artifacts live under `.runtime/review/<timestamp>/`; `latest.json` records the
latest run directory. They are local, ignored build evidence. Remove old
`.runtime/review/<timestamp>` directories when their evidence is no longer needed;
preserve `.runtime/tts` models and character references. The fresh accounts
and tokens in `fixture.json` are test-only credentials; do not publish that file.
Screenshots and the captioned MP4 contain only the seeded fictional story.

This is a layered coverage strategy, not a promise to enumerate every possible
LLM response, GPU failure, network interleaving or browser/device combination.

## CI availability

The workflow definitions are [visible templates](../ci/README.md), not active
GitHub Actions. This repository excludes all dot-prefixed directories, including
`.github/`. References below to CI behavior describe those templates when adapted
to a CI system; local review commands remain executable.

## Fresh-install check

Run `python3 scripts/review/compose-smoke.py` to build the root Compose core
images and exercise a new database, startup migrations and Nginx/GraphQL routing.
This separate check needs only Python 3 and Docker Compose v2 on the host; Java
and Bun run in the build containers. It explicitly uses `.env.example`, isolates
resource names, generates test secrets and refuses unexpected host bind mounts.
It starts no GPU, monitoring or live inference services. Evidence goes under
`.runtime/review/core-compose-*`; generated configuration is private and deleted
on cleanup, together with the test containers, volumes and image tags. Build cache
is retained. Do not interrupt cleanup with a forced process kill.

## Prerequisites and commands

Use Java 21, Docker Compose v2, Node.js 24 LTS, Bun 1.4.5+, FFmpeg and a speech Python
venv. The existing native venv created by `scripts/run-tts.sh` works; CI builds
a smaller CPU venv from `scripts/review/requirements.txt` plus PyTorch 2.7.1.
Set `REVIEW_PYTHON` to use a different interpreter. Install frontend dependencies with
`cd frontend && bun install --frozen-lockfile`. The local runner defaults to
`/usr/bin/brave`; set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` for another Chromium build.
Alternatively install Playwright Chromium and set its executable path. No
browser is downloaded implicitly by the review runner.

For a CPU-only test environment without installing Ming, use:

```bash
uv venv .runtime/review-tools/venv --python 3.11
uv pip install --python .runtime/review-tools/venv/bin/python -r scripts/review/requirements.txt
uv pip install --python .runtime/review-tools/venv/bin/python torch==2.7.1 --index-url https://download.pytorch.org/whl/cpu
REVIEW_PYTHON="$PWD/.runtime/review-tools/venv/bin/python" node scripts/review/run.mjs
```

This CPU environment supports deterministic speech-adapter tests, not `--live-tts`.
The [first recorded review](history/automated-review-2026-11-22.md) documents
measured coverage, the streaming repair and remaining acceptance gaps.

```bash
# Full gates, isolated browser review, evidence and video
node scripts/review/run.mjs

# Faster iteration after unit/static gates have passed
node scripts/review/run.mjs --browser-only

# Add real Ming synthesis/continuity checks and a narrator voiceover
node scripts/review/run.mjs --live-tts

# Also validate focused judgments against a real OpenAI-compatible model
REVIEW_LIVE_LLM_URL=http://your-model-host:8000 node scripts/review/run.mjs --live-tts

# Record a real-model adventure: actual browser speech, travel and multiplayer votes
REVIEW_LIVE_LLM_URL=http://your-model-host:8000 \
REVIEW_LIVE_LLM_MODEL=your-model node scripts/review/run.mjs --browser-only --demo --live-tts

# Re-edit its recorded browser actions and synchronized playback
node scripts/review/render-demo.mjs .runtime/review/<demo-timestamp>

# Re-render the deterministic review short from an existing reviewed run
node scripts/review/render-short.mjs .runtime/review/<timestamp>

# Reuse recorded Ming narration with a fresh set of captures
node scripts/review/render-short.mjs .runtime/review/<new-run> --narration-from .runtime/review/<narrated-run>
```

The backend and frontend run as separate host processes; MySQL and Redis run
in disposable containers. This provides test/data isolation rather than an
untrusted-code security boundary. Ports are selected dynamically and bind to loopback. Compose project names are
unique per run, MySQL uses tmpfs, and Redis persistence is disabled. The backend
uses the packaged JAR with explicit sandbox database/JWT/model configuration;
the frontend uses its established backend/speech proxy overrides.

## The loop

1. **Establish a baseline.** Run Java tests, including disposable MySQL inventory
   tests, frontend coverage, type checking, lint and speech tests. Package the backend.
2. **Launch isolated services.** Wait for database and API health rather than
   assuming startup time. Seed accounts for an owner, participant and outsider.
   Generate a real procedural world and a controlled connected local stage.
3. **Exercise workflows.** Assert API state as well as visible UI. Check negative
   authorization, quantities/effects, narrator controls, map scope switching,
   navigation, speech planning, and post-dialogue travel/arrival.
4. **Collect evidence.** Capture desktop/mobile screens, runtime exceptions,
   accessibility results, test reports, model-call records and failed-test
   traces/videos. Preserve the first failure; do not hide it behind retries.
5. **Review the rendering.** The agent opens representative captures and compares
   light/dark, desktop/mobile, populated/empty/error states against `DESIGN.md`.
   Screenshots alone cannot prove audio quality, touch gestures or multiplayer
   correctness; use their dedicated checks as well.
6. **Fix and rerun.** Classify each finding as a product bug, harness error,
   expected limitation or false positive. Fix the smallest applicable layer.
   Add a regression assertion for behavior that could recur, rerun that journey,
   then rerun the full suite when the repair changes shared behavior.
7. **Produce the short.** Only a passing browser run renders the capability
   short (40 seconds without a voiceover, extended as needed for the real narration).
   Captures show real product screens with a labeled scripted review
   story. Captures default to dark mode (`REVIEW_THEME=light` remains available for
   theme checks). The edit uses locked overview frames, timed close-up cuts and a
   2D-to-3D map cut. `video-quality.json` verifies that encoded frames remain still
   between cuts, with the expected duration, resolution and audio stream.
   Titles and editing are reproducible with FFmpeg; no capabilities are
   invented or presented as live model-quality evidence.
8. **Record the result.** Include commit, commands, counts, visual decisions,
   remaining coverage gaps and artifact paths in the review report. A run is
   complete only when required gates pass and outputs have been inspected.

An automated retry is not an autonomous code repair. The runner emits failures;
the agent reads evidence, edits code and reruns it. It does not grant a model
unrestricted shell access or silently modify source files from CI.

## Coverage layers

| Layer | What it proves | What it does not prove |
| --- | --- | --- |
| Java unit/service tests | Atomic judgment schemas, lifecycle, state rules, movement, inventory/combat, subscriptions | All real-model judgments or deployment combinations |
| Disposable MySQL integration tests | Transactional inventory/combat effects and concurrency | Production-data quality |
| Vitest and coverage | Component/store state transitions, read-through progress, buffering, voice routing, narrator gating, map interactions | Rendered browser geometry or GPU rendering |
| Python speech tests | Prompt separation, segmentation, pacing, acronym/first-person routing, references and audio handling | Perceptual quality from the real Ming checkpoint |
| Real sandbox backend and Chromium | Auth, participant boundaries, actual APIs, map renderers, navigation, responsive layout, focused travel pipeline and UI error paths | Firefox/WebKit, physical touch hardware, arbitrary story semantics |
| Axe plus screenshot review | Measurable accessibility findings and visual evidence at captured sizes | Full WCAG conformance or a substitute for human judgment |
| Explicit live-model acceptance | Real LLM decisions and Ming continuity, pacing and sound | Repeatability without model/version/hardware controls |

The deterministic OpenAI-compatible provider is a test boundary, not a production
fallback. It logs every focused call. The speech boundary replaces only model
inference with a quiet synthetic CPU tone and retains the production adapter,
speech planner and WebSocket protocol. Its own reference files stay in the run
directory. The real Ming model and existing acoustic references are untouched.

## Automated live acceptance

`--live-tts` uses the real Ming checkpoint on `MING_DEVICE` (default `cuda:0`),
with its existing download cache and a fresh run-specific voice reference cache.
It generates narrator, first-person character and follow-up clips; checks PCM
and saved references; then synthesizes a real narrator voiceover for the short.
It requires the full Ming Python environment. Listen to the resulting WAVs to
assess voice quality; nonempty PCM does not prove natural speech.

`REVIEW_LIVE_LLM_URL` adds four real-model checks using the production travel,
encounter and interaction prompts. These check committed versus cancelled travel,
a friendly meeting, and a concrete interaction. Optional model/key overrides are
`REVIEW_LIVE_LLM_MODEL` and `REVIEW_LIVE_LLM_KEY`. These calls use fictional
inputs and mutate no application state. They complement the deterministic
end-to-end pipeline rather than replacing it.

## Content-aware product film

`--demo` records a chaptered, approximately five-minute overview, separate from
nine deterministic browser journeys. It requires `REVIEW_LIVE_LLM_URL`, `--live-tts`
and the full Ming environment. The [coverage and shot plan](product-demo-plan.md)
defines the creator, relationship/alignment, map/empire, gameplay, continuity,
sharing, party, progression and settings chapters. The browser generates and saves
characters/rosters, applies item effects, travels, votes, rolls dice, creates a
bookmark/branch, publishes locally and downloads an actual export. Optional ComfyUI
is explicitly labeled unconnected in captures without a live provider.

The fixture has an explored nine-room town, known destinations, injured Mira,
two healing draughts and fictional prior knowledge/progression. Actual Web Audio
PCM records narrator/character playback. Promotional commentary uses a separate
warm, gentle, reflective presenter with unhurried phrasing. It never overlaps the
in-product excerpts or changes the product narrator's identity. A raw-only color
slate calibrates recording clocks; complete utterances are retained.

`veyloria-overview.mp4` starts at native 1920×960, browser zoom 100%, with a 120px
caption rail. Purposeful focus zooms settle into locked holds. Tall dialogs remain
fully framed; the travel camera moves closer through native controls. Chapters are
embedded in the MP4 and listed in `demo/chapters.json`. The raw takes, PCM WAVs,
API evidence, `edit-decisions.json`, `export-quality.json` and reviewed contact
sheet live under `demo/`; commentary lives under `demo-narration/`.

Completed chapter prefixes may be reused from review-owned artifacts. The
combiner checks their gates, calibrates both clocks, preserves original raw takes,
copies original playback PCM and records nested source provenance. Original
failures remain visible; a reused prefix is not a claim that its entire source
run passed. Every required chapter must be present before the final film exports.

- `REVIEW_DEMO_NARRATION_FROM=<run>` reuses commentary only when its presenter
  profile and every script line match exactly.
- `REVIEW_DEMO_CREATION_FROM=<run>` reuses completed chapters through
  `map-generated`; `REVIEW_DEMO_PREFIX_THROUGH=<beat>` selects a later completed
  boundary in an already composed capture.
- `REVIEW_DEMO_PHASE=postlude` captures the real export/settings/closing flow for
  repair/review and appends it to the explicitly selected verified prefix.
- `scripts/review/revoice-chapter.py <run> <chapter> <script-file>` regenerates one
  promotional chapter with the same isolated acoustic reference; use the full
  speech Python environment and `PYTHONPATH=services/tts:.runtime/tts/ming`.
- `node scripts/review/combine-demo.mjs <new-run> <source-run> [last-beat]` performs
  the same guarded composition directly. Keep source artifacts until the final
  review has been accepted.

Inspect the moving edit and audio in addition to automated gates. The static
review short and its freeze checks remain separate from this workflow overview.

## Live acceptance and expansion

Before a release, run a separate isolated acceptance session against the actual
LLM and Ming checkpoint: a cold cache/startup, narrator plus two character voices,
first-person actions without quotes, acronym normalization, uninterrupted long
sentences, stop/resume and next-message buffering. Listen to generated clips;
check the model revision, seed, instruction and reference identity. Record timing
and quality separately from deterministic pass/fail evidence. Do not synthesize
from users' saved conversations as test fixtures.

Grow browser coverage from a tracked route/action matrix: auth and account,
character authoring/rosters, new roleplay initialization, party/invites,
conversation lifecycle, inventory/equipment/gifts, dice, knowledge/bookmarks,
combat/votes, map journeys, images, publishing/community, settings/import/export,
admin authorization and failure/reconnect states. Keep deferred integrations
visible: email delivery and ComfyUI require dedicated sandbox providers, and
production destructive/admin operations must use disposable accounts/data.

## References

The harness follows Playwright's official [test configuration](https://playwright.dev/docs/test-configuration),
[API testing](https://playwright.dev/docs/api/class-apirequestcontext) and
[video retention](https://playwright.dev/docs/videos) interfaces. CI uploads
only curated evidence with the official
[artifact action](https://github.com/actions/upload-artifact).
