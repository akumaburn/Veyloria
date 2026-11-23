# Documentation

Current behavior is described by the guides below. The canonical database SQL
and live code-first GraphQL schema remain authoritative for exact structures.

| Guide | Contents |
| --- | --- |
| [Project README](../README.md) | Features, prerequisites, startup, licensing |
| [Automated review](automated-review.md) | Disposable sandbox, browser evidence, review/fix loop and capability short |
| [Product film plan](product-demo-plan.md) | Complete feature coverage, chapter script and capture acceptance gates |
| [Repository organization](repository-layout.md) | Directory ownership, validation and local-only artifacts |
| [Developer guide](DEVELOPER_GUIDE.md) | Architecture, atomic LLM decisions, progress, residents, narrator mode, testing |
| [API](API.md) | Verified GraphQL examples, authentication, subscriptions, speech endpoints |
| [World and local maps](world-and-local-maps.md) | Local/global UI, stage authority, movement, arrival, rendering and travel |
| [Narrative combat](narrative-combat.md) | Party votes, encounters, transactional consequences and journey recovery |
| [Inventory](inventory.md) | Ownership, permissions, item rules, transfers and narrative continuity |
| [Voice playback](voice-playback.md) | Ming startup/download, Voice Design, references, routing and buffered read-through |
| [Character rosters](character-rosters.md) | Generating and saving a connected cast, story initialization and pacing |
| [Character relationships](character-relationships.md) | Directional affinity, alignment, prompts and APIs |
| [Prompt templates](prompt-templates.md) | LLM chat template detection and configuration |
| [Git history sanitization audit](history-publication-audit.md) | Single-commit publication snapshot, hidden-directory policy and private recovery boundaries |
| [Public release assessment](public-release.md) | Publication checks, limitations and operator checklist |
| [Security policy](../SECURITY.md) | Vulnerability reporting and deployment boundaries |
| [Contributing](../CONTRIBUTING.md) | Development conventions and review workflow |
| [Backend](../backend/README.md) | Backend setup and configuration |
| [Scripts](../scripts/README.md) | Native speech launcher and operational utilities |
| [UI review](history/ui-review.md) | Dated visual/accessibility and interaction verification |
| [Product](../PRODUCT.md) / [design](../DESIGN.md) | Product intent, current constraints and interface conventions |
| [Change log](../CHANGELOG.md) | Changes collected in the current development snapshot |

## Historical records

Reports live under [`history/`](history/README.md). They retain their original
context and are not current setup instructions:

- [Feature parity](history/FEATURE_PARITY.md): ActiveJ to Vert.x migration.
- [Settings plan](history/settings-consolidation-implementation.md) and
  [completion report](history/SETTINGS-IMPLEMENTATION-COMPLETE.md): earlier settings consolidation.
- [Message metadata plan](history/message-metadata-migration-plan.md): superseded schema proposal.
- [November 13 database/GraphQL audit](history/schema-graphql-audit.md): dated deployment snapshot.

Copyright 2024-2026 Amir Farhad Eslampanah. Repository code and documentation use
the [Apache License 2.0](../LICENSE); third-party components retain their own notices.
