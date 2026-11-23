# Veyloria complete product overview

## Direction

An approximately five-minute, chaptered product film. The previous one-adventure short
omitted the creator, relationship and empire systems. This overview starts with
creation, follows one adventure, then shows continuity and sharing. Every major
player/creator system has an explicit evidence requirement below. Inference waits
are edited out; actions and their visible results stay in.

Dark mode. Native 1920×960 capture at browser zoom 100%, with a separate 120px
caption rail in the 1080p export. Establish each context before a short, deliberate
focus zoom. Hold the focus still. Keep the closer native 3D camera from the last
revision; editorial zoom must not enlarge the map controls.

Promotional commentary: a smooth, warm, romantic storyteller—gentle mid-range
pitch, clear natural timbre, unhurried phrasing and a tender, reflective baseline.
Use lyrical but concrete copy, with room to breathe. Keep this acoustic identity
separate from the fixed in-product narrator and character voices. No commentary
over the actual voice playback excerpts.

## Coverage and shot script

| Chapter / approximate time | Actual demonstration | Required evidence / voiceover direction |
| --- | --- | --- |
| Begin a story · 0:00–0:16 | Browse populated story templates and solo/group creation options | Templates, genres and roster selection are visible. “Every world begins with a longing: a place to belong, and a story still waiting to be told.” |
| Create a character · 0:16–0:30 | Enter a premise, run real character generation, inspect the generated identity/personality, save | New private character persists. Show the editor's identity and personality sections. |
| Create a connected cast · 0:30–0:53 | Generate three characters together; inspect shared history, roles, alignment and feelings; save the roster | Real LLM output, three saved members, named roster. “Some stories begin with one voice. Others begin with lives already entwined.” |
| Relationships and alignment · 0:53–1:14 | Open all saved connections, select a directed relationship, reverse it; inspect alignment overview | Populated connection overview, two independent directions, alignment values—not empty panels. |
| Shape the world · 1:14–1:29 | Configure and generate a separate procedural world through the UI | Visible controls followed by actual terrain/roads/locations. Keep the adventure map intact. |
| A living conversation · 1:29–1:48 | Narrator and Mira playback in order | Actual audible in-product speech; complete utterances, no commentary overlay. |
| Supplies and consequences · 1:48–2:06 | HP 12/20 → use a draught → HP 17/20; stack 2 → 1 | Actual item mutation and visible result. |
| Places and arrival · 2:06–2:32 | Explore the town map; choose the inn; show arrival narration, resident greeting and updated marker | Nine connected explored rooms, committed movement and greeting. |
| Empires and the wider world · 2:32–3:03 | Toggle the 2D empire overlay, show cultural regions; compare the 3D overlay, then begin real travel | Populated boundaries/cultures and changed terrain overlay. Actual five-step journey and arrival. |
| Choose together · 3:03–3:18 | Show two votes and resolve a combat round | Vote count, new round, HP/narration/options. |
| Chance and continuity · 3:18–4:15 | Roll dice with a purpose; create a bookmark; show branching controls, world state, knowledge and achievements | Actual roll/result/history and saved bookmark. Authoring-only knowledge/AI controls are explicitly shown in Narrator mode. |
| Share and keep the story · 4:15–5:00 | Publish the sandbox adventure, open its community listing; create a party/invite code; export data; show model/settings and illustration integration | Real local listing publication and export. Optional ComfyUI is presented as an integration when no live provider is connected, never as a generated result. |
| Close · 5:00–5:06 | Return to Quick Start and close at 100% | “A world to wander. People to remember. And a story that becomes yours.” |

Times are targets. Extend the film when a generated result or complete spoken
excerpt needs it. Completeness means the product's major player/creator systems,
not every CRUD button, administrative endpoint or deployment dashboard.

## Capture acceptance

- Use the actual isolated backend, database, browser, external LLM and Ming model.
  Existing user conversations, acoustic references and production data stay untouched.
- Generated characters/rosters must be created through their UI flows and saved.
  Seeded adventure state is fictional prior history, not fabricated interface output.
- Empires, relationships, maps, community and progression must contain meaningful
  data. A blank panel does not meet a feature's acceptance requirement.
- Assert generated/saved state and interaction effects against the sandbox API.
  Native browser scale, both playback roles, actual travel progress and all required
  chapter beats are export gates.
- Handle incidental route encounters with real party votes and retreat/resume.
  Preserve these actions in raw footage/evidence and label compressed elapsed travel.
- Review the complete contact sheet plus focus transitions, target bounds and audio
  levels. Speaker metadata, non-silent PCM and a voice design prompt do not by
  themselves certify perceptual voice quality.
- Keep unavailable integrations explicit. Never manufacture an image, external
  delivery or background simulation and present it as an application result.

## Deliverables

`veyloria-overview.mp4`, raw browser video, actual playback WAVs, promotional
commentary, API evidence, `edit-decisions.json`, `export-quality.json` and a reviewed
contact sheet. Record gaps and evidence honestly; preserve failed-take logs.

## Capture and review record — 2026-11-23

The film combines completed chapter captures from separate disposable sessions.
Creator footage comes from the 16:37 take, play and continuity through achievements
from the 16:57 take, and the repaired export/settings close from the 17:08 take.
Each selected action completed its UI/API gates. Later failures in those earlier
takes remain in their original reports; they are not reported as passing full runs.
Raw takes, normalized clocks and nested composition provenance are retained.

Validation: 252 frontend tests across 49 files, frontend and capture type checks,
nine standard browser journeys, four live-model judgment checks, real browser
export/download and encoded stream/complete-excerpt checks. The completed creator
footage includes a saved individual character, three saved roster members and a
populated relationship/alignment overview. Both empire overlays are populated.

The full review found and fixed a real export failure: the frontend sent an
unsupported `includeSystemData` field in `ExportFilter`. The unused metadata
checkbox is removed; a regression test verifies the selected-section contract
and download flow. Final frames are inspected for complete dialog bounds, held
navigation flashes, actual publication and the export confirmation.

Community publication currently exposes a title, synopsis and ratings; the public
reader does not render the conversation transcript. The film describes listings
accurately and makes no claim of public transcript playback.
