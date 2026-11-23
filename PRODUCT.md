# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Primary users are players of long-form AI roleplay on a public service with open
registration. They run one story across many sessions, speak through a player
character, and expect the world, the cast, and the consequences of past turns to
stay consistent as they move and act.

Two confirmed audiences share that service:

- Group players who form a party, invite other people into a conversation, and
  hold one shared world state.
- Community readers and authors who publish stories, rate them, and build on
  story templates.

Self-hosters form a secondary audience. The repository ships Docker Compose, a
production deploy script, and a manual developer setup that runs the whole stack
on one machine.

## Product Purpose

Veyloria is a fantasy RPG generator. A player starts a world, and one roleplay
engine carries it forward: AI characters, world state, character sheets, dice
rolls, procedural maps, travel, image generation, and stories the community shapes
together.

The world does not drift. Success means the same place, the same cast, and the
same consequences when a player returns a week later. A scene that contradicts
what the world already recorded is a failure even when the scene reads well.

The product is multiplayer and AI-run. A party shares one conversation, and the
world keeps changing on its own schedule, so the story continues whether or not
any player is online.

## Positioning

Continuity is the claim. A generated world is worth keeping only if it remembers
what happened in it, so every surface reads and writes one recorded state.

Every agent reads one authoritative stage, and narration must obey it. The world
map chooses the node the party occupies; the local map chooses the room, hall, or
street inside that node; movement follows recorded connections and roads. A model
cannot move the party into a place the map does not contain, and a rejected move
leaves the stage unchanged.

The engine pairs that spatial authority with accounting. A dedicated agent
collects one inventory, stat, relationship or world operation per judgment.
Services validate and apply those effects before dependent narration. Achievement
awards use explicit service/API operations, not invented model IDs. The player
inspects the same state the agents read.

Inference is provider-agnostic. The same product runs on a local llama.cpp server
pool or an OpenAI-compatible endpoint, and structured generation uses a strict
JSON schema where the provider supports one.

## Operating Context

A player works inside a conversation. A roleplay conversation carries a story
template, a cast with alignment and directional feelings, a character sheet with
stats and inventory, dice, bookmarks, branches, achievements, and an optional
party.

Narrator mode enables optional authoring controls per conversation; player mode
hides state overrides, history rewriting and AI internals while retaining normal
play. The toggle is shared by chat and map and defaults off after reload.

The map view shows a generated world and the interior layout of the current node.
A journey moves the party along roads in steps, and random events fire per step.

The community tab holds published stories and shared templates. Settings hold
per-agent model choice and generation parameters.

Operators deploy the stack with Docker Compose or the production deploy script,
and watch Prometheus metrics and Grafana dashboards.

## Capabilities and Constraints

Verified in the repository:

- Chat with streamed tokens over GraphQL subscriptions and WebSocket.
- Single, group, and multi-user conversations with invitations and roles.
- Roleplay pipeline: Highlighter/RAG, focused encounter/travel/interaction
  judgments, sequential single-operation bookkeeping, event narration and
  Skeptic review. Optional Muse suggestions and asynchronous Casting Director
  proposals cannot authorize effects. Battle Master separates adjudication,
  prose and choices; staffed arrivals receive a separate resident greeting.
- Authoritative stage service (`StageService`) over a world map and a per-node
  local map, with validated movement and gateway rules.
- Procedural terrain: simplex noise, Whittaker biomes, road networks from a
  minimum spanning tree and A* paths, 22 location types, 8 procedural cultures.
- 2D canvas map and a ray-marched 3D map with fog of war.
- Character rosters generated as one connected cast, with alignment and
  directional affinity.
- ComfyUI image generation, Ming Voice Design playback with stable character
  references and narrator/character switching, knowledge retrieval, achievements, and
  data export and import.
- Built-in story templates across fantasy, sci-fi, horror, mystery, romance, and
  other genres, plus community publishing.

Technical constraints:

- Backend: Java 21, Quarkus 3.36, SmallRye GraphQL, reactive MySQL client with no
  ORM, Redis, JWT authentication.
- Frontend: React 18, TypeScript, Vite 8, Bun, Zustand, Apollo Client, Tailwind
  CSS 4, Radix UI, Three.js.
- One consolidated `backend/src/main/resources/db/schema.sql` owns the database
  shape. Bundled idempotent startup upgrades and guarded column additions keep
  existing installations compatible; no Flyway/Liquibase runner is used.
- Application data uses GraphQL. Ming speech uses a separate same-origin `/tts`
  HTTP/WebSocket service.

Explicitly undecided: the content range of the service. No age gate, content
warning, or moderation policy exists today, and the product does not yet state
which themes it allows.

Two parts of the product statement are not built yet:

- There is no background world cycle. World state advances only while a turn or a
  journey step runs, so a world does not change while every player is offline.
- The template library still ships sci-fi, horror, western, and historical genres.
  Fantasy is the stated identity; narrowing the library is a product decision, not
  a technical limit.

## Brand Commitments

The name Veyloria binds.

The wordmark, palette, and display type remain the incumbent implementation and
may change: an underscore wordmark, orange `#fa520f` and sunshine `#ffb83e`
accents, Inter for text, and Instrument Serif for display type.

## Evidence on Hand

- `README.md`: feature inventory, architecture diagrams, setup steps, API surface.
- `docs/DEVELOPER_GUIDE.md` and `docs/API.md`.
- `docs/world-and-local-maps.md`: the stage contract, movement rules, and 3D
  shader performance measurements.
- `docs/character-rosters.md` and `docs/character-relationships.md`: roster
  generation rules and the affinity model.
- `docs/history/ui-review.md`: a completed accessibility and responsive pass, with
  verification notes.
- Backend tests (JUnit 5, Mockito, Testcontainers) and frontend tests (Vitest,
  Testing Library).

Absences that future work must not fill by invention:

- No user research, interviews, or analytics.
- No testimonials, customer names, press, or case studies.
- No pricing, plan names, or commercial terms.
- No marketing surface. The root route sends a signed-in user into the app and
  every other visitor to login.
- No legal review of the terms and privacy pages.

## Product Principles

1. The stage holds authority. Every agent and every view reads the same world and
   room state, and narration cannot create a place the map lacks.
2. Continuity outranks novelty. Consequence, state, and cast memory survive
   across sessions.
3. The player holds the decision. Characters act inside the scene; the player
   chooses what happens next.
4. State stays inspectable. Anything an agent changes is visible in a panel the
   player can read.
5. Local inference stays a first-class path. No feature may require a hosted
   model.

## Accessibility & Inclusion

Target: WCAG 2.1 AA.

The current interface provides a skip-to-content link, keyboard focus containment
and restoration in dialogs, accessible names on controls, contrast fixes, and a
reduced-motion override. `docs/history/ui-review.md` records verification at 320, 390,
768, 1024, and 1440 pixels in headless Chromium.
