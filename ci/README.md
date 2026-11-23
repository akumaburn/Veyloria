# CI templates

The GitHub workflow templates live in `github-workflows/`. They are deliberately
inactive: this repository excludes every dot-prefixed directory, including
`.github/`, from publication. GitHub Actions discovers workflows only under
`.github/workflows/`, so a normal push of this source does not enable them.
Do not describe these templates as hosted checks that have already run.

Local validation remains available:

```bash
python3 scripts/check-public-tree.py
python3 scripts/check-docs.py
gitleaks git --log-opts=--all --redact=100
RUN_INVENTORY_DB_TESTS=true ./backend/gradlew -p backend test
(cd frontend && bun run typecheck && bun run lint && bun run test -- --run)
node scripts/review/run.mjs
python3 scripts/review/compose-smoke.py
```

The full review needs the prerequisites in
[automated review](../docs/automated-review.md); the Compose smoke check needs
Python 3 and Docker Compose v2. The templates contain build, dependency/security,
secret and browser checks, with no automatic deployment or image publication.
Activating GitHub Actions later would require an explicit exception to the
no-hidden-directory policy. Another CI service can use the local commands above.
