# Public release assessment

Reviewed **2026-11-23**. The sanitized source snapshot is ready for public
**development** publication: prior private ancestry has been replaced by one
initial commit. See the [sanitization audit](history-publication-audit.md).
Production deployment remains unapproved because of the runtime advisories and
deployment controls below. No hosting visibility changed and nothing was pushed.

## What was verified and corrected

| Area | Result |
| --- | --- |
| Repository license | Apache 2.0; Copyright 2024-2026 Amir Farhad Eslampanah. Frontend metadata agrees. |
| Tracked source | No application `.env`, runtime conversations, voice references, model weights, uploads or backups in the current tracked tree. Local assistant/runtime state is ignored. |
| Git history | Replaced all private ancestry with one initial commit. The legacy key, old author records, personal configuration and vendor history are not part of the new history. The source/history scans pass, including the dedicated key rule. |
| Dependency installation | Frozen Bun lockfile; Bun 1.4.5+ and Node.js 24 LTS. Scoped transitive overrides require the newer Bun lockfile format. |
| Frontend dependencies | `bun audit` returned no advisories after dependency/lockfile updates. Vite 8 and Vitest 4 replace vulnerable older tooling. |
| Backend platform | Quarkus 3.36.1 → 3.36.3, including the patch for GHSA-qcxp-gm7m-4j5v. This reduces, but does not eliminate, runtime advisories. |
| Documentation | Fixed missing assistant-document links, private clone URL, prerequisites, import aliases and executable test examples, schema-upgrade claims, optional-service setup and development environment loading. `python3 scripts/check-docs.py` validates local Markdown file/heading links. |
| Compose configuration | Forwarded external LLM URL/model/key and ComfyUI settings; corrected startup migration privileges; fixed the default frontend CORS origin; made llama.cpp model filename configurable; bound monitoring and optional llama.cpp ports to loopback. |
| CI | Preserved inactive workflow templates under visible `ci/`; no `.github/` directory is tracked. GitHub Actions is inactive under the no-hidden-directory policy. Local validation remains available; nothing publishes or deploys automatically. |

Gitleaks uses its default rules, a dedicated legacy-key rule and two narrow
public-fixture exceptions. No scanner can prove that every possible secret or
private fact has been found. The original history is retained only in a private
backup outside the repository; do not upload or reintroduce it. The publication
commit uses a non-deliverable author email instead of the original personal/work
addresses. No dot-prefixed directory is tracked; standard configuration dotfiles
remain. The [history audit](history-publication-audit.md) records the exact scope
and the [CI guide](../ci/README.md) explains workflow availability.

## Runtime dependency findings remain

The following are **package-version matches**, not proofs that every advisory is
reachable through Veyloria. They are not suppressed from the assessment.

| Scope | Checked state | Remaining findings |
| --- | --- | --- |
| Frontend | Final Bun lockfile, application and development dependencies | No reported advisories. |
| Java runtime | OSV batch query of 193 resolved Maven coordinates after the Quarkus patch | 29 advisory identifiers across 9 packages: 1 critical, 11 high, 17 moderate. Families include Jackson, Netty and OpenTelemetry. |
| Native speech environment | `pip-audit` over 83 installed packages in the existing pinned Ming environment | 70 scanner entries / 41 distinct advisory identifiers across 6 packages: accelerate, diffusers, pytest, setuptools, starlette and transformers. Entries include repeated IDs; these are not 70 independently verified application vulnerabilities. |
| Containers / CUDA / model assets | Core images built for smoke testing; GPU image and OS-layer vulnerability coverage incomplete | A complete image/SBOM and model-runtime security review is still required before a binary or hosted release. |

In particular, the remaining critical Java match is
[GHSA-c4c3-7fpv-j4q5](https://github.com/advisories/GHSA-c4c3-7fpv-j4q5),
Netty SNI routing with fragmented TLS ClientHello messages. The default backend
serves HTTP behind Nginx; the package match alone does not establish that this TLS
path is exposed in that topology. The Quarkus authentication/path-normalization
advisory [GHSA-qcxp-gm7m-4j5v](https://github.com/advisories/GHSA-qcxp-gm7m-4j5v)
no longer matched after updating to 3.36.3.

Before a production release, move to a supported, patched Quarkus dependency set
and validate the full backend suite and clean deployment again. Upgrade the
speech HTTP stack and inference dependencies with actual Ming synthesis tests;
blindly overriding the pinned Transformers/Diffusers stack would not establish
model compatibility. Some installed Python tools are development-only, and some
upstream findings have no fix listed for the installed release line. Do not
represent the pinned speech environment as vulnerability-free.

Raw audit files remain under ignored `.runtime/public-audit/`; they are local
review evidence, not distributed application assets. Re-run scans near the actual
publication date because advisories change.

## Validation evidence

The isolated product review completed with **439 backend tests, 252 frontend
tests, 88 speech tests and 9 browser journeys**, plus frontend and browser type
checks, lint, production builds, GraphQL contract checks, screenshots and the
capability short. Its local run identifier is `2026-11-23T18-58-08-459Z`.
That complete run preceded the final Quarkus patch; the patch is checked again
separately in this audit. Passing deterministic inference/CPU speech tests does
not validate live provider behavior or perceptual voice quality.

The clean Compose smoke check (`python3 scripts/review/compose-smoke.py`) builds
both application images, initializes a fresh
database using the actual root Compose schema/grant scripts, and checks backend
startup, frontend serving and GraphQL through Nginx. It uses a separate project,
random credentials, private ports and disposable volumes, then removes them.
After Quarkus 3.36.3, all **439 backend tests passed with zero skips**, and the
browser-only review passed all **9 journeys** again (local run
`2026-11-23T19-05-06-303Z`). The fresh root Compose install also passed startup,
Nginx, GraphQL and default-origin CORS checks with the patched images.
The maintained smoke run identifier is `core-compose-xzfw4g2w`. It exposed and resolved the
MySQL bootstrap health race and non-root log-volume ownership issue in addition
to the grant mismatch. The maintained smoke runner is included in the inactive CI templates.

Local lint has **0 errors and 180 warnings**; these are not described as fixed.
The templates have not run on a public destination and no GitHub workflows are
active in this snapshot.
The filesystem Trivy job is not a substitute for the resolved Java and installed
Python dependency audits above.

## Publication steps

1. Run `python3 scripts/check-public-tree.py`, `python3 scripts/check-docs.py`,
   `git diff --check`, `bun audit` in `frontend/`, and
   `gitleaks git --log-opts=--all --redact=100`. Confirm `git rev-list --count HEAD`
   reports one commit before the first push. Scan the current source separately
   after any uncommitted changes.
2. Configure a new/empty public destination explicitly. No remote is configured
   after sanitization. Push only `master`; do not import private backup refs,
   fetch/merge the original repository or mirror-push old history. See the
   [history audit](history-publication-audit.md) for recovery boundaries.
3. Keep the disclosed runtime advisories and [security policy](../SECURITY.md)
   visible. Do not market a hardened production deployment or stable release.
4. Enable private vulnerability reporting, secret scanning/push protection and
   dependency alerts on the host where available. Choose a CI service and branch
   protection deliberately: `ci/` contains templates, not active GitHub Actions.
   These hosting settings were not changed by repository sanitization.
5. Publish only source and documentation. Exclude `.env`, `.runtime`, model caches,
   backups and user data. Do not attach raw traces, tokens, private conversations,
   private history bundles or audit environments. Review media before uploading.

## Deployment boundaries

The root README provides a local development/demo quick start. Before sharing a
hosted instance, configure TLS, exact allowed origins, strong secrets, trusted
proxy boundaries, resource limits, monitoring access, backup/restore and tested
schema upgrades. Existing MySQL volumes retain their old grants; fresh grants
only apply during first initialization. The canonical `schema.sql` contains DROP
statements and is only for a fresh or intentionally reset database.

The speech service has no independent user authentication; its WebSocket origin
check is not an authorization boundary. Protect `/tts` at the public proxy rather
than exposing GPU inference anonymously. Avoid assuming narrator mode establishes
a backend privilege role. Horizontal scaling and interrupted in-memory generation
recovery need separate validation; the removed CI `--scale backend=3` command
conflicted with the fixed host port and was not a supported deployment recipe.

`deploy-prod.sh` creates a deployment directory and configuration templates. It
skips tests and does not provision a complete secure service; run CI/review first
and treat its output as an operator-maintained starting point.

Repository licensing does not relicense dependencies, model weights or generated
content. [Ming's checkpoint card](https://huggingface.co/inclusionAI/Ming-3.0-tts-0.5B)
and [inference source license](https://github.com/inclusionAI/Ming-3.0-tts/blob/main/LICENSE)
identify MIT licensing; retain upstream notices when distributing the runtime.
Other GGUF models, image checkpoints and external providers have their own terms.

Copyright 2024-2026 Amir Farhad Eslampanah.
