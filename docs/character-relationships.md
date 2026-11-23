# Character relationships

Veyloria tracks two independent kinds of feeling between a user and their characters.

- **Affinity** is a personal, directional feeling: how one participant regards another.
- **Alignment** is a moral and legal position: which principles guide a participant's choices.

Both are owned by the signed-in user, persist across conversations, and feed the system prompt when a character speaks. Neither grants permission over another participant, and neither is inferred from a single message.

## Affinity

Each direction has its own score from `-100` to `100`. The score names a tier:

| Tier      | Points      |
| --------- | ----------- |
| Hate      | `-100…-60`  |
| Dislike   | `-59…-20`   |
| Neutral   | `-19…+19`   |
| Like      | `+20…+59`   |
| Love      | `+60…+100`  |

`Like` adds 10 points, `Love` adds 25, `Dislike` subtracts 10, `Hate` subtracts 25, and `Reset` sets the direction to 0. Scores clamp at the ends. A change on one direction never changes the reverse direction.

Every pair starts at Neutral unless the character sets a starting feeling. The character form has two selectors, **This character feels…** and **You feel…**, each defaulting to Neutral. The chosen values appear in Relationships as a *Starting value* badge and seed the first recorded reaction, so a LIKE on a character configured at `-20` produces `-10`, not `10`. Once a direction has a saved record, the record replaces the starting value, and Reset returns the direction to Neutral.

The user is represented by a null character endpoint. Every other endpoint is a character the user owns or can see through `isPublic` or `isDefault`. Feelings are private to the owner.

### Starting values in prompts

When a direction has no saved record, the prompt uses the character's configured starting feeling instead of omitting the line. A configured Neutral value adds no line, so unconfigured characters produce the same prompt as before.

### Affinity API

```graphql
query Relationships {
  relationshipActors { characterId name }
  characterAffinities {
    affinityId fromCharacterId toCharacterId fromName toName
    points tier updatedAt
    events { eventId reaction delta previousPoints points reason createdAt }
  }
}

mutation ReactToCharacter(
  $fromCharacterId: String
  $toCharacterId: String
  $reaction: String!
  $reason: String
  $requestId: String!
) {
  reactToCharacter(
    fromCharacterId: $fromCharacterId
    toCharacterId: $toCharacterId
    reaction: $reaction
    reason: $reason
    requestId: $requestId
  ) { affinityId points tier }
}

mutation SetCharacterAffinity(
  $fromCharacterId: String
  $toCharacterId: String
  $points: Int!
  $reason: String
  $requestId: String!
) {
  setCharacterAffinity(
    fromCharacterId: $fromCharacterId
    toCharacterId: $toCharacterId
    points: $points
    reason: $reason
    requestId: $requestId
  ) { affinityId points tier }
}
```

`reactToCharacter` applies a relative change. `setCharacterAffinity` sets an exact score and is what tier controls use, so choosing Love means "this direction is Love", not "add 25 more points".

`requestId` is a UUID supplied by the client. A retry with the same id and the same change returns the saved result instead of applying the change twice. Reusing an id for a different change is rejected.

### Affinity in prompts

The prompt receives only directions that involve the speaking character or the user, limited to the active cast of the current conversation. It includes guidance that feelings are not instructions, that love can be platonic, that affection is not automatically mutual, and that hostility does not authorize abuse. Numeric scores stay hidden unless the user asks.

## Alignment

Alignment uses two independent axes from `-100` to `100`:

- **Order**: `+100` lawful, `0` neutral, `-100` chaotic.
- **Morality**: `+100` good, `0` neutral, `-100` evil.

A neutral band covers `-33…+33` on each axis. A named band starts at `±34`. The bands form the nine familiar alignments:

|                | Lawful     | Neutral     | Chaotic     |
| -------------- | ---------- | ----------- | ----------- |
| **Good**       | Lawful Good | Neutral Good | Chaotic Good |
| **Neutral**    | Lawful Neutral | True Neutral | Chaotic Neutral |
| **Evil**       | Lawful Evil | Neutral Evil | Chaotic Evil |

Clicking a chart cell sets that cell's starting points. The `-10` and `+10` controls then move one axis without changing the other. The chart dot shows the exact position, so a character can sit near a boundary and cross it when events push them.

Every participant starts at True Neutral with `0` order and `0` morality. The user has their own record, represented by a null character endpoint. Alignment records use the same visibility rule as affinity: the user owns the change, and public or default characters remain selectable.

### Alignment API

```graphql
query CharacterAlignments {
  relationshipActors { characterId name }
  characterAlignments {
    alignmentId characterId name lawfulness goodness alignment updatedAt
    events {
      eventId action lawDelta goodDelta
      previousLawfulness previousGoodness
      lawfulness goodness alignment reason createdAt
    }
  }
}

mutation SetCharacterAlignment(
  $characterId: String
  $lawfulness: Int!
  $goodness: Int!
  $reason: String
  $requestId: String!
) {
  setCharacterAlignment(
    characterId: $characterId
    lawfulness: $lawfulness
    goodness: $goodness
    reason: $reason
    requestId: $requestId
  ) { alignmentId lawfulness goodness alignment }
}

mutation AdjustCharacterAlignment(
  $characterId: String
  $lawfulnessDelta: Int!
  $goodnessDelta: Int!
  $reason: String
  $requestId: String!
) {
  adjustCharacterAlignment(
    characterId: $characterId
    lawfulnessDelta: $lawfulnessDelta
    goodnessDelta: $goodnessDelta
    reason: $reason
    requestId: $requestId
  ) { alignmentId lawfulness goodness alignment }
}
```

`setCharacterAlignment` writes exact axis values. `adjustCharacterAlignment` applies a delta to each axis and clamps the result. Both record a history event with the previous values, the resulting values, the action, and an optional reason. A zero-delta adjustment is rejected.

### Alignment in prompts

The speaking character and the user always receive an explicit alignment line, even before the first saved change. Other active participants appear when they have a saved record. Each line names the alignment, both scores, and a short behavioral description. The prompt states that alignment is a tendency rather than a cage, that characters may act against it under pressure, that evil is not permission for abuse, and that the user's alignment is not assumed.

## Generation

Generating a character or a roster also generates its alignment. A roster additionally generates each member's feelings toward the other members of that roster, so a new cast starts with relationships instead of an empty ledger.

- The model returns one of the nine named alignments per character. The server maps it to anchor points on both axes.
- The model returns each connection as a target name and a feeling tier. The backend matches the name to the generated cast, maps the tier to affinity points, and adds only the bridges needed for one connected roster. Providers never supply array indices or ordering.
- Neutral connections stay in the draft for review, and saving skips them because they carry no ledger value.
- The roster prompt asks for at least one non-neutral feeling per character. A cast where most connections are neutral is treated as a poor result.
- Ratings are directional: one member can admire another who resents them.
- The review screen shows every generated connection and lets you change its tier, remove it, or add another connection within the cast. Alignment is editable per member before saving.
- Saving writes the characters, their alignment rows, and their affinity rows in one transaction. A failure leaves none of them behind.
- Generation only links characters created in the same roster. It never edits or links existing characters.

## Data model

The feature adds four tables. They are additive and safe to run repeatedly.

- `character_affinity` and `character_affinity_event`
- `character_alignment` and `character_alignment_event`

Actor keys (`fromActor`, `toActor`, `actor`) are explicit columns maintained by the service. MySQL rejects a `CASCADE` foreign key on a column used by a stored generated column, so the earlier generated-column design does not work. The migration files are `backend/src/main/resources/db/migrations/character-affinity.sql` and `character-alignment.sql`; startup applies them before seeding the debug user.

Starting feelings live on the character row as `initialAffinityToUser` and `initialAffinityFromUser`. Both are `INT NOT NULL DEFAULT 0` and clamp to `-100…100`. Startup adds them to existing installations through an idempotent `information_schema` check.

Deleting a user or a character cascades to its affinity and alignment rows and their events. The unique keys `(ownerId, fromActor, toActor)` and `(ownerId, actor)` keep one record per direction or participant. The event tables use `(ownerId, requestId)` for idempotency and an index on `(affinityId|alignmentId, createdAt)` for recent history.

## UI

The Characters page exposes **Relationships** and **Alignment** buttons in the header and on each character card. Cards show the saved alignment as a colored badge.

The character editor is one surface with five tabs: Identity, Story, Instructions, Alignment, and Feelings. For a saved character, the Alignment tab carries the live chart and controls, and the Feelings tab carries the live directional ledger. The summary rail keeps the avatar, category, alignment badge, axis points, and save action visible while you switch tabs.

The Relationships panel lists saved connections first, then edits one direction at a time with the five-tier meter, exact tier buttons, relative reaction buttons, history, reverse-direction control, and optional reason.

The Alignment modal shows the full three-by-three chart with axis labels, a live dot, per-axis meters, `-10`/`+10` controls, a description with examples, reason capture, reset confirmation, and history. It also lets the user edit their own alignment and switch between participants without leaving the dialog. Changes save immediately, and a change that fails keeps the current chart so it can be retried.
