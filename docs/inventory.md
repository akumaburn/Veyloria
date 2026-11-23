# Inventory and item use

Inventory is scoped to one conversation and exactly one owner: an AI character or a player. Player inventories are private to that player (and site administrators); character inventories are visible to conversation participants. The inventory panel asks the server for permissions and eligible recipients. Narrator mode only exposes editing controls when the caller actually has host or conversation administrator permissions.

## Permissions and multiplayer consistency

- Hosts and active conversation administrators may grant and remove items and act on party inventories.
- Active participants may use, equip, and give their own items. They may also control inventories belonging to characters they own. Reading a character sheet does not grant control of its inventory.
- Giving is a one-way gift. Recipients must be the conversation owner, an active player participant, or an active conversation character. An item cannot move into another conversation.
- A gift can contain a whole stack or a positive number of units from it. A partial gift preserves the source item and creates a recipient stack with the same metadata. Gifted units arrive unequipped. This is not a trade or an offer requiring acceptance.
- Every writer uses a transaction and locks the conversation before inspecting current ownership and quantities. A pending transfer cannot authorize a subsequent use by the previous owner. Concurrent consumption of the last unit succeeds once. Failed effects roll back all changes.
- Grants stack only when name, description, category, and metadata match and neither stack is equipped. `stackable: false` keeps grants separate. Gifts retain their own stack identity instead of merging potentially distinct items. Ambiguous removals by name fail rather than choosing an arbitrary variant.
- Committed writes publish inventory invalidations, containing IDs rather than private item details. Authorized inventory and stat queries refresh on every client. Manual mutations also await local refetches; controls block repeat submission while pending.

## Item rules

Categories `weapon`, `armor`, `equipment`, `accessory`, and `shield` are equippable by default. Category `consumable` consumes one unit on use; consuming the final unit removes the stack. Other items cannot be consumed merely because the caller invokes Use. A reusable item can explicitly allow use without losing quantity. Quest and miscellaneous items are not usable by default.

Optional JSON metadata in `AddInventoryItemInput.properties`:

```json
{
  "consumable": true,
  "equippable": false,
  "usable": true,
  "transferable": true,
  "stackable": true,
  "slot": "main_hand",
  "effects": [{ "statName": "HP", "delta": 15 }]
}
```

Each field is optional. Explicit flags override category defaults, except that consumables cannot be equipped. A declared equipment slot is exclusive for that owner; equipping a replacement clears the previous item in the same slot. The host can choose a slot in the Add Item form. Unslotted equipment remains compatible with freeform RPG systems. Equipping a stack denotes one held item; its quantity remains unchanged. Equipment does not invent numerical bonuses or enforce a particular game's hand, weight, or armor rules.

Effects modify existing numeric character stats, clamped between zero and the stat's declared maximum. Missing stats and invalid effects fail without consuming anything. Character-held items target their owner by default. Player-held items with mechanical effects require selection of a character the caller controls in the same conversation. Player stat sheets are not part of the current data model; no fictional player HP is silently created. Item use without explicit mechanical effects records story use only; descriptions are never interpreted as executable rules. The host's Add Item form can configure a consumable's stat effect for a character inventory.

## Story continuity

`inventory_activity` records grants, removals, equipment changes, uses, and gifts in the same transaction as the inventory change. Records retain the resulting item, source ownership, and explicit effect target, including when the final unit is consumed. An additive startup migration creates the table on existing installations.

The agent context includes the triggering player's current inventory alongside character sheets and the latest 20 committed item actions. The narrator and accountant receive those actions as already-applied history and are instructed not to repeat their inventory or stat effects. AI inventory actions run in order and finish before the narrator receives refreshed inventory state. Battle prompts include actual party inventories, stats, and character IDs. Battle effects use only the current round’s changes; an optimistic round check prevents competing resolutions from committing the same round, and final-round loot is applied before the resolver returns. AI-generated and battle-result inventory changes also go through the same service, including quantity-aware removal and multiplayer invalidation. This provides grounding for subsequent story turns; using an item does not itself trigger a new AI turn.

## Verification

Run the disposable MySQL integration suite with:

```sh
RUN_INVENTORY_DB_TESTS=true ./backend/gradlew -p backend test --tests '*InventoryServiceDatabaseTest'
```

It checks ownership, invalid recipients, partial gifts, slot replacement, concurrent grants and last-unit consumption, reusable items, effect targets, clamping, and rollback. It uses a dedicated Testcontainers database and never resets the development database.

Frontend action and permission coverage is in `InventoryList.test.tsx`.

Combat rounds now commit item changes, numeric stat changes, battle state and chat narration together. See [Narrative combat and travel](narrative-combat.md) for encounter flow and party voting.

## LLM bookkeeping boundary

The Accountant proposes one inventory operation per model request (`{"change":{...}}`) or explicitly stops with `{"change":null}`. It cannot decide travel, combat, stats or relationships in that request. Accepted proposals are staged in server code; duplicate operation identities, contradictory ownership, malformed responses and exhausted operation limits fail collection rather than partially accepting a combined result. Once collection succeeds, inventory operations are applied in order through this service and awaited before narration. Item-declared effects remain authoritative; separate stat proposals must not repeat them. See [Atomic LLM decisions](DEVELOPER_GUIDE.md#atomic-llm-decisions) for schemas, limits, retries and transaction boundaries.
