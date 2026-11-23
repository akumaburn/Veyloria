# Automated product review — 2026-11-22

This records the first complete isolated product-review workflow. For current
commands and boundaries, see [automated review](../automated-review.md).

## Verified

- Backend: 439 tests, zero failures/errors/skips, including disposable MySQL
  inventory/combat tests and the new stream ordering regression.
- Frontend: 251 tests across 48 files; application and browser-harness type checks,
  lint and production build pass. Existing large bundle warnings remain.
- Speech: 88 Python tests pass. An existing Starlette deprecation warning remains.
- Browser: nine Chromium journeys pass; 20 screenshots cover populated chat,
  characters/party, local/global maps, inventory, voice settings, arrival, combat,
  route smoke checks, mobile layouts and a dark theme.
- Accessibility: Axe reports zero violations on the captured voice settings
  surface. This is not a whole-application accessibility certification.
- GraphQL: 221 frontend operations and 36 fragments validate against the sandbox.
- Real LLM: four focused travel/encounter/interaction checks pass against the
  configured Swift model, using production prompts and fictional inputs.
- Real Ming: narrator, character and follow-up synthesis produces PCM and separate
  saved acoustic references. Narration chapters drive the animated short. Audio
  artifacts and model revision are recorded in `live-speech.json`; perceptual
  voice quality still requires listening.

## Bug found and fixed

A rapid OpenAI-compatible SSE burst produced scrambled character speech in the
real browser: words from later chunks appeared inside earlier words, despite
correct provider output. The buffer's periodic drain and transport-triggered
completion drain could run on different Vert.x contexts at once. Both invoked
a consumer that appends to the same mutable response builder.

`SSEStreamBuffer` now serializes batch consumption and terminal callbacks.
Incoming SSE enqueue remains lock-free. Cancellation/abort also synchronize
with a running batch. `SSEStreamOrderingTest` holds the first batch while the
transport marks completion, asserts that a second batch cannot overtake it,
and verifies all 100 events and exactly one completion in order. The browser
regression additionally checks the complete character utterance after travel.

Coverage collection also included built `dist/` bundles after a production build.
Scoped Vitest instrumentation to application source, excluding test/type files,
so repeated review runs measure the same source baseline.

## Review decisions

Opened and inspected desktop and mobile conversation, map, arrival, combat and
dark-settings captures. Corrected harness selectors to match actual tabs and
keyboard-accessible narrator controls, waited for content instead of URL changes
alone, and used the local layout's percentage coordinate convention in fixtures.
These were harness repairs rather than product regressions. No new design-rule
suppressions were added.

The scripted provider now preserves opening combat HP, uses separate judgment
contracts, and emits distinct character, arrival and resident utterances. It
cannot establish real-model narrative quality; separate acceptance checks cover
only the specified real-model cases.

## Remaining coverage

The existing frontend unit coverage is approximately **35.8% of statements and
65.1% of branches**. The generated `coverage-gaps.json` lists the largest gaps.
Passing these suites does not prove every code path. Browser coverage remains
Chromium-only; physical touch, Firefox/WebKit, real email and ComfyUI delivery,
full authoring/admin workflows and arbitrary model/network interleavings need
additional fixtures and acceptance cases from the plan's coverage matrix.

The runner emits evidence and stops on failure. The agent performs visual review
and source repairs; the CI process does not rewrite source code autonomously.
