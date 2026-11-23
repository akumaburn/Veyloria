# Git history sanitization and publication audit

Updated **2026-11-23** after the owner authorized replacing development history
with the current source as the first commit.

**Verdict: the sanitized source snapshot is suitable for a public development
push.** The publication repository has one root commit, no old ancestry, tags,
backup refs or remote-tracking refs, and no tracked hidden directories. This is
not a production security certification; the remaining runtime advisories are
listed in the [release assessment](public-release.md).

## What was removed from publication

The original private repository had 204 reachable commits. An expanded review of
12,402 unique blobs and the preceding 203 commit objects identified a historical
`CHATLLM_API_KEY` literal used by the old streaming and shutdown handlers. The
literal was absent from the current source, but deletion commits could not remove
it from the old ancestry. Generic secret rules missed the short key; a dedicated
rule was added to `.gitleaks.toml` and retained in the publication snapshot.

The private history also contained two personal/work author email addresses,
assistant permission and inferred-preference files, local machine paths/private
host settings, and more than ten thousand vendored `node_modules` paths. None of
those historical versions or original author records are ancestors of the new
commit. Required source/build inputs, including the Gradle wrapper and Bun
lockfile, remain in the snapshot.

The new commit uses the requested author name, **Amir Farhad Eslampanah**, with
`noreply@veyloria.invalid`, a deliberately non-deliverable address. This avoids
publishing the personal/work addresses from the original history. Repository-local
Git identity uses the same public address; global Git identity is unchanged.

## Hidden directories and retained configuration

Every dot-prefixed directory is excluded from tracking at every depth, including
`.github/`, `.impeccable/`, `.agents/`, `.codex/` and `.runtime/`. The ignore rule
`.*/` prevents routine additions, while `scripts/check-public-tree.py` checks the
actual index so even a forced addition can be detected.

Standard configuration **files** remain tracked: `.gitignore`, `.dockerignore`,
`.gitleaks.toml`, `.env.example`, frontend environment/formatter examples and
component-specific ignore files. No private `.env` is published. Dotfiles are
not hidden directories.

Because `.github/` is excluded, workflow definitions are preserved as inactive
[visible CI templates](../ci/README.md). GitHub Actions will not run automatically
from this snapshot. Local build, test, browser-review and smoke-check commands
remain available. An active GitHub workflow would require a later explicit
exception to the no-hidden-directory policy.

## Verification

The final publication checks verify:

- Exactly one reachable root commit and only the intended local `master` ref.
- The old tip cannot be resolved from the new repository; there is no alternate
  object database, backup ref or old reflog connecting it to private history.
- No tracked dot-prefixed directory, private environment file, dependency tree
  or Git bundle. `python3 scripts/check-public-tree.py` passes.
- The complete tracked source and new Git history pass Gitleaks, including the
  dedicated legacy-key rule and the narrow documented fixture exceptions.
- Literal checks find none of the historical key or personal/work author email
  addresses in the publication source or commit metadata.
- Local Markdown links and Compose example configuration remain valid.

The earlier application checks remain the build baseline: 439 backend tests,
252 frontend tests, 88 speech tests, 9 browser journeys and a fresh Compose smoke
install. Sanitization changes repository metadata, publication documentation,
ignore rules and workflow placement; it does not change application behavior.

## Recovery and pushing

A verified private Git bundle and the original Git metadata are retained in an
owner-only backup **outside this repository**. The original model caches, voice
references, local assistant state and other ignored working files remain local;
they are not part of the new commit. Do not upload the private backup or attach
raw audit evidence to a public issue.

The publication repository has no configured remote. Add the intended public
repository explicitly and push only the new branch:

```bash
git remote add origin <public-repository-url>
git push -u origin master
```

Use a new/empty public destination. No remote was contacted or pushed during
sanitization. Do not fetch, merge, tag or mirror-push the original private
history into the public repository. If an existing destination already contains
the old history, a fresh branch push alone does not remove it.

Sanitization does not invalidate an old credential. If a legacy deployment or
another service still accepts the historical key, retire or rotate it separately;
its validity was not tested against any server. The original key value is
intentionally absent from this report.

Copyright 2024-2026 Amir Farhad Eslampanah.
