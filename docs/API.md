# Veyloria API

The API is code-first SmallRye GraphQL. Annotated Java resolvers and explicit
source resolvers define the runtime schema. No GraphQL schema snapshot is checked
in; inspect the running backend for the current contract. On 2026-11-22, live
introspection exposes
**78 query fields, 123 mutation fields and 40 subscription fields**. Counts of
frontend operation documents are a different measure.

## Endpoints

| Purpose | Development address |
| --- | --- |
| GraphQL HTTP | `POST http://localhost:8300/graphql` |
| GraphQL subscriptions | `ws://localhost:8300/graphql`, `graphql-transport-ws` |
| GraphQL UI | `http://localhost:8300/q/graphql-ui/` when enabled |
| Quarkus Dev UI | `http://localhost:8300/q/dev/` in dev mode |
| Health | `/health`, `/health/live`, `/health/ready` on the backend |
| Prometheus metrics | `/metrics` on the backend |
| Speech health / voices | `/tts/health`, `/tts/api/voices` through the frontend |
| Speech plan / generation | `POST /tts/api/speech-plan`, WebSocket `/tts/ws/tts` |

The frontend proxies `/graphql` and `/tts` in development and container deployment.
Ming speech is a separate service, not a GraphQL resolver. See
[voice playback](voice-playback.md) for its request/response contract and startup.

## Authentication

JWT access/refresh tokens are validated by `JWTAuthProvider` and
`GraphQLAuthContext`. Use `Authorization: Bearer <access-token>` for HTTP.
Registration and login accept positional string arguments:

```graphql
mutation Register($username: String!, $email: String!, $password: String!) {
  register(username: $username, email: $email, password: $password) {
    accessToken refreshToken expiresIn
    user { id username email role }
  }
}

mutation Login($username: String!, $password: String!) {
  login(username: $username, password: $password) {
    accessToken refreshToken expiresIn
    user { id username displayName }
  }
}

mutation Refresh($token: String!) {
  refreshToken(refreshToken: $token) { accessToken refreshToken }
}

query Identity { me { id username displayName role } }
```

Browser WebSockets authenticate using `connection_init.payload.authorization`,
since they cannot attach arbitrary HTTP headers:

```javascript
const socket = new WebSocket('ws://localhost:8300/graphql', 'graphql-transport-ws');
socket.onopen = () => socket.send(JSON.stringify({
  type: 'connection_init',
  payload: { authorization: `Bearer ${accessToken}` },
}));
// Wait for connection_ack before sending subscribe frames.
```

Use `frontend/src/lib/websocket-client.ts` inside the app; it owns reconnects,
authentication and subscription deduplication. Do not create parallel connections.

## Data conventions

Most identifiers and timestamps are GraphQL `String`, including conversation,
character, map, inventory and battle IDs. Do not substitute `ID!` in variable
declarations without checking the actual field type. The schema also uses
`BigInteger` and `BigDecimal` for relevant numeric values. Many domain JSON
properties are serialized strings (for example inventory `properties` and battle
`actionOptions`/`battleData`); some internal JSON fields are deliberately ignored
and cannot be selected. Use the actual typed fields rather than assuming a JSON
scalar or introspecting Java field names.

`sendMessage` accepts `SendMessageInput`, including optional `isBase64Encoded`.
`editMessage(id, content)` takes a string; follow the current frontend encoding
and resolver behavior instead of declaring the obsolete `Base64` scalar.
Subscriptions return domain objects, not uniform `{ event, payload }` wrappers.

## Conversations and processing

```graphql
query ConversationState($id: String!) {
  conversation(id: $id) {
    id title groupState currentSpeakerId processingOnly
    processingStatus { conversationId runId sequence stage label active updatedAt }
  }
  messages(conversationId: $id, limit: 30) {
    items { id content role messageType characterId battleId createdAt }
    cursor hasMore
  }
}

mutation Send($input: SendMessageInput!) {
  sendMessage(input: $input) { id content role createdAt }
}

subscription Stream($id: String!) {
  messageStream(conversationId: $id) {
    id conversationId role content streamingContent isComplete error messageType
    characterId battleId
    character { id name personality gender pronouns ageYears }
  }
}

subscription Processing($id: String!) {
  groupStateChanged(conversationId: $id) {
    id groupState currentSpeakerId processingOnly
    processingStatus { conversationId runId sequence stage label active updatedAt }
  }
}
```

Use `sendGroupMessage` for multi-character rounds and `stopGeneration` to cancel
active generation. Streaming content is provisional until completion. Progress
frames with `processingOnly:true` describe backend work and must not overwrite
persisted turn state or attribute narrator work to the last character. Compare
run IDs/sequences, retain terminal progress frames and load the conversation
snapshot after reconnect. See [progress contracts](DEVELOPER_GUIDE.md#progress-and-arrival-residents).

## World, local maps and travel

```graphql
query Stage($id: String!) {
  worldState(conversationId: $id) {
    currentScene currentLocation currentLocationId currentArea currentAreaId
    dayNumber timeOfDay weather mood
  }
  activeScene(conversationId: $id) {
    sceneId name location description characters { id name }
  }
  localMap(conversationId: $id) {
    mapId locationId locationName currentAreaId currentAreaName visitedAreaIds
    areas { localAreaId name description x y isGateway isEntry }
    connections { fromAreaId toAreaId label }
  }
  worldMaps(conversationId: $id) {
    mapId name mapScale gridWidth gridHeight
    locations { locationId name x y z isDiscovered }
  }
  activeJourney(conversationId: $id) {
    journeyId status currentStep totalSteps toLocationId toLocationName
  }
}

mutation StartJourney($map: String!, $destination: String!, $characters: [String!]) {
  beginJourney(mapId: $map, toLocationId: $destination, characterIds: $characters) {
    journeyId status currentStep totalSteps
  }
}

subscription JourneySteps($id: String!) {
  travelJourneyStep(conversationId: $id) {
    journeyId stepNumber totalSteps narration battleId interruptReason
    journeyComplete journeyInterrupted x y z
  }
}
```

Normal travel uses `beginJourney`, `resumeJourney` and `cancelJourney`. The legacy
`travelToLocation` is an authorized host scene skip. Direct entity positioning,
location/map authoring and ordinary stage movement retain their service guards;
map mutations cannot bypass active combat or an unfinished journey. Travel
events may pause a route and expose a battle ID for a chat link. See
[movement invariants](world-and-local-maps.md) and [combat flow](narrative-combat.md).

## Inventory, stats and combat

```graphql
query Sheet($id: String!, $character: String!) {
  characterStats(conversationId: $id, characterId: $character) {
    statId statName statValue statMax statType statCategory textValue isVisible
  }
  characterInventory(conversationId: $id, characterId: $character) {
    inventoryId itemName itemDescription category quantity isEquipped properties
  }
  inventoryAccess(conversationId: $id, characterId: $character) {
    canManage canModify recipients { id kind name canReceiveEffects }
  }
  activeBattle(conversationId: $id) {
    battleId status roundNumber currentNarration actionOptions battleData canManage
    votes { userId optionIndex roundNumber }
  }
}

mutation UseItem($item: String!, $target: String) {
  useInventoryItem(inventoryId: $item, targetCharacterId: $target) {
    inventoryId quantity isEquipped
  }
}

mutation GiveItem($item: String!, $recipient: String!, $quantity: Int!) {
  transferInventoryItem(inventoryId: $item, targetCharacterId: $recipient, quantity: $quantity) {
    inventoryId characterId itemName quantity
  }
}

mutation Vote($input: VoteActionInput!) {
  voteAction(input: $input) { battleId userId optionIndex roundNumber }
}

mutation Resolve($battle: String!, $round: Int!) {
  resolveBattleRound(battleId: $battle, roundNumber: $round) {
    battleId status roundNumber currentNarration actionOptions
  }
}
```

Choose exactly one inventory owner: `characterId` or `userId`. Player inventories
use `userInventory`; gift recipients use `targetCharacterId` or `targetUserId`.
A last-unit use may return null because the stack was removed. Refetch inventory
and stats on invalidation; events need not contain private item details. See
[item rules and transactions](inventory.md). Battle voting/resolution includes
the expected round to reject stale clients. Effects and narration commit together.

Narrator mode controls frontend visibility only. It does not replace server
authorization: inventory and battle management controls also use server-provided
permissions. Public player actions remain available with narrator mode off.

## Other API domains

| Domain | Representative current operations |
| --- | --- |
| Characters / rosters | `characters`, `characterGroups`, `generateCharacter`, `generateCharacterRoster`, `createCharacterRoster` |
| Affinity / alignment | `characterAffinities`, `characterAlignments`, `setCharacterAffinity`, `adjustCharacterAlignment` |
| Group membership | `conversationParticipants`, `inviteUserToConversation`, `respondToInvite`, `updateParticipant` |
| Story authoring | `storyTemplates`, `createRoleplayConversation`, `bookmarks`, `createBranch` |
| Knowledge / agent config | `knowledgeEntries`, `agentRuns`, `agentModelConfig`, `updateAgentModelConfig` |
| Community | `publishedStories`, `publishStory`, `rateStory`, `communityTemplates`, `submitTemplate` |
| Images | `comfyuiStatus`, `generatedImages`, `generateImage`, `imageGenerationProgress` |
| Settings / data | `settings`, `updateSettings`, `exportData`, `importData` |

The maintained browser documents live in `frontend/src/graphql/operations.ts`,
`roleplay-operations.ts`, and `components/map/graphql/map-operations.ts`.
Corresponding typed models live in `frontend/src/graphql/types.ts`.

## Errors and validation

GraphQL can return HTTP 200 with an `errors` array; inspect it as well as network
status. Authentication, conversation participation and domain permissions are
checked on the server. Do not interpret a failed judgment or mutation as a
successful no-op. Reconcile server state after reconnects and stale-round errors.
Rate limits and allowed origins are configurable in `.env.example` and
`application.properties`.

With the backend running, validate the complete client contract without writing
application files:

```bash
cd frontend
node --experimental-strip-types scripts/check-graphql.ts
```

Set `GRAPHQL_SCHEMA_URL` for another backend. For exhaustive field and input
shapes use live introspection or the GraphQL UI rather than the legacy schema
file. The November 22 check validated 221 frontend operations and 36 fragments.
