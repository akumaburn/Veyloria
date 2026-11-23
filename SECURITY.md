# Security policy

Veyloria is under active development. There is no supported stable release or
security response SLA; fixes target the current development branch. Public
source availability does not mean the application is hardened for untrusted,
Internet-facing multi-tenant deployment.

## Reporting a vulnerability

Use the hosting repository's **Security → Report a vulnerability** private
reporting form if the maintainer has enabled it. If it is unavailable, open an
issue asking for a private reporting channel without including exploit details,
credentials, private conversations or personal information. No separate security
email address is currently designated.

A private report should include the affected revision, component, prerequisites,
minimal reproduction using synthetic data, impact and any proposed fix. Redact
logs, tokens and provider keys. Do not test accounts or servers you do not own.

If a real secret was committed, revoke or rotate it first. Deleting the current
file does not remove Git history; coordinate history cleanup with the maintainer.
See [GitHub's sensitive-data guidance](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).

## Running safely

- Generate a random JWT secret and unique database/cache/Grafana passwords.
  Keep `.env`, backups, uploads, voice references and runtime artifacts private.
- Keep auth bypass and development mode disabled on shared deployments; turn off
  detailed GraphQL errors. Mock inference is a local demonstration mode.
- Put TLS and access controls in front of the application. Bind data services,
  monitoring and inference to a trusted network. Trust proxy headers only from
  your actual reverse proxy; the default configuration assumes that boundary.
- The speech adapter has no independent user authentication. Keep it on loopback
  or a private network and protect `/tts` at the public reverse proxy before
  exposing GPU inference. Origin validation is not authentication.
- Startup performs schema upgrades and needs scoped DDL privileges. Back up and
  test upgrades before using an existing database; the reset script deletes data.
- Models and external providers have separate licenses and privacy terms. LLM
  text is untrusted input; narrator mode is a UI preference, not a security role.
- Review dependency advisories for JavaScript, Java, Python, models and container
  images before each deployment. A frontend audit alone is not a full audit.

See the [publication assessment](docs/public-release.md) and
[isolated review guide](docs/automated-review.md) for verification boundaries.

Copyright 2024-2026 Amir Farhad Eslampanah.
