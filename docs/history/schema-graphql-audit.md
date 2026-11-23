# Database and GraphQL audit — 2026-11-13

> Dated audit snapshot. Counts and local deployment observations below describe
> 2026-11-13, before later inventory, combat, progress and voice changes. Current
> setup and contract checks are documented in [the developer guide](../DEVELOPER_GUIDE.md)
> and [API reference](../API.md); this is not a current deployment health report.

The active local development database and GraphQL contract pass the checks below after the repairs in this change. The application remains available at http://localhost:3000, with the backend at http://localhost:8300.

The active database is **127.0.0.1:3306/chatllm**. A disposable MySQL 8 database, loaded from `backend/src/main/resources/db/schema.sql`, supplied the canonical metadata for comparison. It was removed after each comparison. MariaDB JSON aliases, integer display widths, default-expression spelling, and equivalent foreign-key rules were normalized before comparing.

| Check | Coverage | Result |
| --- | --- | --- |
| Database definitions | 51 tables, 618 columns, 238 indexes, 109 foreign keys | No remaining differences |
| Referential integrity | All 109 foreign keys | No orphaned references |
| JSON integrity | All 41 JSON columns | No invalid JSON |
| Backend SQL preparation | 501 static statements and 24 dynamic templates with representative substitutions | All prepare successfully; no data-changing statements executed by this check |
| Live GraphQL schema | 71 queries, 112 mutations, 40 subscriptions, 155 application types | Introspection succeeds |
| Frontend contract | 200 operations and 34 fragments, including inline component documents | All validate |
| Runtime integration | 83 checks through the frontend proxy | All pass |
| Regression suites | 59 backend tests and 80 frontend tests | All pass |
| Frontend build and code generation | Type check, production build, in-memory code generation | Pass |

## Repairs

- Confirmed the earlier `message.seq` repair and its indexes. Upgraded message timestamps to the canonical `DATETIME(6)` definitions without changing existing timestamp values. A private database backup was taken first.
- Set application MySQL sessions to UTC before their connections enter the pool. The local database server uses UTC−03:00, while backend date handling expects UTC. Verified a newly stored conversation timestamp against the current UTC time. Server-wide timezone settings and historical data were not changed.
- Preserved omitted GraphQL input fields separately from explicitly supplied nulls. Partial profile, character, conversation, settings, and world-state updates now leave unrelated fields intact. Added regression coverage for omitted identity fields, explicit nulls, false values, and empty lists.
- Kept seeded achievement identifiers such as `ach-combat-002` as strings throughout catalog loading, awards, user achievement mappings, and subscription event mapping. Existing identifiers were not rewritten.
- Removed a character-existence predicate against the nonexistent `character.isDeleted` column; character deletion removes the row.
- Matched the frontend's LLM status selection to the live `running` field using the `isRunning: running` alias.
- Corrected code-generation document patterns and added the live `BigInteger`/`BigDecimal` scalar mappings.

## Runtime coverage and data preservation

The runtime checks created a temporary account, private characters, conversations, a group conversation, roleplay conversation, party, world map, location, message, inventory item, character stat, scene, and bookmark. Checks exercised populated nested fields, settings/profile changes, partial-update preservation, message editing/reactions, pagination, achievements, and cleanup. The account and fixtures were removed afterward. Existing users and conversations were not used as test fixtures.

External LLM/image generation, email, destructive administrator operations, and live subscription delivery were not invoked. Subscription documents were validated and the backend subscription regression suite passed. Dynamic SQL preparation checks representative substitutions rather than every possible runtime combination.

## Separate Docker database

The inactive Docker database at **127.0.0.1:3307/chatllm** is a separate legacy dataset: it has 15 tables, is missing 37 canonical tables, and is missing 36 columns within tables that already exist. It is **not** the database used by the running development backend. It was inspected read-only and must be upgraded before that Docker deployment is used with this backend. It was not reset or migrated during this audit.

Detailed counts and the missing Docker definitions are recorded in [schema-graphql-audit-results.json](schema-graphql-audit-results.json).

## Recheck the frontend contract

With the backend running:

```sh
cd frontend
bun run check:graphql
```

To check another endpoint, set `GRAPHQL_SCHEMA_URL`. This command loads source documents with the existing codegen dependencies, validates operations and standalone fragments, exits nonzero on errors, and writes no generated files.

The UTC pool initialization follows the documented [Quarkus pool customization](https://quarkus.io/guides/reactive-sql-clients/#customizing-pool-creation) and [Vert.x connection initialization](https://vertx.io/docs/4.2.7/vertx-mysql-client/java/) mechanisms.
