# Narrative combat and travel

Combat uses the existing Battle Master, party votes, inventories, stats, chat stream and world maps. It is a narrative rules system: the model adjudicates attempts and consequences; the server authorizes and validates the resulting state changes. It does not implement a separate tactical grid, initiative or a fixed damage/dice formula.

## Story flow

1. A travel step can roll an encounter. Peaceful meetings pause the route without starting a fight. The travel narrator supplies prose only. A separate strict hostility judgment runs only for a rolled encounter; its validated result may establish combat. Embedded combat/outcome tags have no authority, and other step types cannot start combat.
2. Ordinary story turns use a separate encounter judgment to establish an immediate confrontation. The Accountant cannot combine combat detection with bookkeeping. Hypotheticals, memories and friendly meetings must not start combat. The host can also start an encounter from the Battle tools, supplying context from the story.
3. The Battle Master reads recent chat, the scenario, current world state, party roster, existing stats and inventories. It first adjudicates one chosen attempt, then requests narration from that fixed result, then separately generates choices only for an ongoing battle. A single outcome enum controls terminal state; prose and choice generation cannot revise the mechanical result. Its opening establishes stakes and offers approaches; opening stat/item effects and terminal outcomes are rejected. Opening enemy health remains the declared starting health.
4. An active encounter appears in chat and on the map with **Open fight in chat**. The link `/chat/:conversationId?battle=:battleId` opens the Battle tool, including on mobile. Every saved combat narration also links to its specific encounter. History remains accessible after the fight ends.
5. Players vote on an approach or propose a custom one. Proposals are attempts, not instructions that can force success. Up to six choices are allowed; existing choices keep their indices. At least one disengagement option is provided. One vote per player is restored from the database and may be changed until resolution.
6. The host/active conversation admins can resolve after at least one vote. Other active members can resolve after all current party users, including the conversation owner, have voted. Departed participants' votes are ignored. Ties choose the first listed option.
7. Each accepted round commits the battle state, numeric stat changes, item effects/loss/loot, inventory activity, and a narrator message in one database transaction. A conversation lock and round comparison prevent duplicate commits. Invalid or unavailable effects roll back the entire round. Events publish after commit using the actual GraphQL `Battle` shape.
8. `VICTORY`, `DEFEAT` and `RETREAT` persist as terminal outcomes. Outcome narration remains in chat and the Battle panel. The next ordinary story turn sees the committed outcome and must not repeat damage, item use or rewards. The host can override an outcome explicitly; overrides do not manufacture loot.
9. Travel stays paused until the initiating traveler or host chooses **Continue journey**, or **Abandon route**. Continuing is refused during active combat. Abandonment leaves the party at its actual waypoint; a new route can start from that roadside position. Neither a retreat nor a defeat teleports the party to another map node.

## Movement and narration boundaries

- Ordinary chat during combat may express intent and character reactions. The Accountant cannot apply combat stat/item changes or move the party during that active encounter. The Battle choices resolve mechanical combat consequences.
- The legacy immediate-travel mutation is a host-only scene skip. It saves an arrival narration and establishes the destination scene before NPC reactions; players use narrated journeys.
- Stage movement, immediate travel and direct map repositioning are blocked during active combat. Direct repositioning and immediate travel cannot bypass an unfinished journey.
- A traveling party has no local room stage. Intermediate waypoints update the actual route location rather than placing the party inside a nearby landmark. Cancelling a route does not snap the scene back to the departure node.
- Arrival commits positions, marks the destination discovered, establishes the destination stage and scene, adds traveling characters to that scene, and persists completion before publishing completion or triggering NPC reactions.
- Paused routes and their completed steps persist across reconnects/restarts. Only traveling routes restart their timers. A resumed route continues after its completed encounter step; it does not reroll or replay that step's time cost.
- Travel controls move the initiating player and the selected active introduced AI characters. Other human players retain their own map positions; this does not introduce an implicit teleport for every connected user.

## Item and stat rules

Combat item changes use the same inventory service and activity history as ordinary item controls. `use` references an existing inventory item and applies its declared effects exactly once; a consumable loses one unit, a reusable item remains. `remove` records loss without applying effects; quantities and variant ambiguity are checked. `add` uses the existing grant/stack rules. The Battle Master is instructed to grant justified victory loot only and to avoid duplicating declared item effects as stat changes.

Only existing numeric stats of active conversation characters can change, clamped to zero and their maximum. Human player accounts still have no independent combat health/stat sheet. Defeat is a story outcome, not an automatic deletion or death of a character. Battle narration must keep movements within the current encounter scene; map travel remains an explicit operation.

## Recovery and validation

All judgment calls use the shared strict [atomic decision contract](DEVELOPER_GUIDE.md#atomic-llm-decisions): one complete schema-valid result, one schema-repair retry, and explicit failure rather than guessed approval or partial JSON recovery. Generation retries make no database writes.

Opening-generation failures remove the empty reservation and invalidate clients so the encounter can be retried. Failed later generations leave the current round and its votes intact. Expected round numbers on votes and resolution reject stale clients. Starting a second active battle is rejected under a conversation lock. Story messages use deterministic event IDs to avoid duplicate history on replay.

The additive `journey-encounters.sql` startup migration extends existing journey statuses with `PAUSED`. No existing map, battle or message rows are reset.

Run `RUN_INVENTORY_DB_TESTS=true ./backend/gradlew -p backend test --console=plain` for the backend suite, including isolated MySQL combat/inventory tests. Frontend tests cover persisted voting, custom approaches, duplicate submission prevention, links, outcomes and access-controlled host tools. Browser verification uses actual components with mocked GraphQL responses at desktop and mobile sizes; it is not a live multi-account LLM playthrough.
