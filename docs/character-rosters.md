# Character rosters

On Characters, choose **Generate roster**, describe a location/world/history (10–6,000 characters), and select 2–12 members. The page opens on **Rosters**, which lists every roster generated together; switch to **Characters** to see the full collection. Generation returns unsaved drafts. Review the setting, backgrounds, connections, alignments, and feelings, then save the whole roster. The review screen edits each member's alignment and every generated feeling before saving. Saved members are new private roleplay characters; existing characters are never updated.

The server generates the ensemble in one request. The model returns one `characters` array; each entry carries its own name, role, age, pronouns, alignment, biography, and named connections. The backend owns the structural work: it enforces the requested count, validates unique names, normalizes identity fields, maps alignment and feeling labels, matches connection names, and guarantees one connected cast. Every character's saved instructions include the original setting and the final cast; their backstory includes the matched relationships. This context remains available when using a character individually.

`createCharacterRoster(inputs: [RosterMemberInput!]!, groupName: String, setting: String)` also records a `CharacterGroup`. The group stores the roster name, the shared setting, and the ordered member ids in the same transaction. Deleting a group or removing a member never deletes the character. Rosters can be renamed and deleted from the Rosters view.

`generateCharacterRoster(prompt: String!, count: Int!)` returns draft `CharacterDraft` objects. Each draft carries the generated character, its starting alignment (`alignment`, `lawfulness`, `goodness`), and its feelings toward other roster members (`relationships`). `createCharacterRoster(inputs: [RosterMemberInput!]!)` assigns fresh IDs and authenticated ownership, inserts all members in one database transaction, and writes each member's alignment row and directional affinity rows in the same transaction. It publishes an update only after commit. Validation and insert failures do not leave a partially saved roster. Generation results are not cached or added to the character list until saved. Closing the generation dialog aborts the browser request and ignores late replies. Closing is disabled during a save.

Generated alignment uses the nine named alignments and stores the matching anchor points: lawful and good `+70`, chaotic and evil `-70`, neutral `0`. Generated feelings use the five affinity tiers and store `LIKE 20`, `LOVE 60`, `DISLIKE -20`, `HATE -60`; neutral feelings create no row. Every generated connection, including a neutral one, appears in the review screen so it can be changed or removed. Feelings are directional, so one member can like another who dislikes them in return. Single-character generation also returns a `CharacterDraft` with an alignment, and `createCharacter` writes that alignment row when `lawfulness` or `goodness` is supplied.

For OpenAI-compatible servers, roster requests use a strict JSON schema with the character array bounded to exactly the requested size. The schema fixes the nine alignment names and the five feeling tiers, so a provider cannot invent values. Connections are requested as target names, not indices. The backend matches exact, case-normalized, title, and short-form names; it drops self, duplicate, and unresolved references and adds only the bridges needed for one connected cast. If the model repeats a character name, the backend keeps the first occurrence and renames later ones with a role or numeric suffix, so a duplicate name cannot discard the roster. Providers never manage array positions. Relationship reasons are also checked against the source character's role: a current professor or teacher is not described as a recently expelled student unless that person's backstory establishes the earlier transition. An unexplained contradiction is replaced with a role-neutral reason and logged. `LLM_ROSTER_DISABLE_THINKING` defaults to `true` and sends `chat_template_kwargs: {"enable_thinking": false}` only for roster generation. This prevents reasoning models from exhausting the output budget before producing roster JSON. Set it to `false` for providers that do not support that extension. Regular chat and single-character generation retain their existing behavior. A roster request has a ten-minute timeout and an output budget scaled to cast size.

The native llama.cpp path applies the same roster schema through `json_schema` and scales `n_predict` with cast size. Provider behavior follows the [vLLM reasoning controls](https://docs.vllm.ai/en/v0.31.0/features/reasoning_outputs/) and [llama.cpp server schema options](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md).

## Saved rosters

A saved roster is one group. The Rosters view is the default Characters view, so a generated cast stays identifiable after individual editing. A roster card shows its name, shared setting, member count, and each member with the current alignment. The review dialog suggests a roster name from the setting and lets the reviewer change it before saving.

Group operations are owner-scoped:

- `characterGroups` returns the current user's groups with ordered `characterIds`.
- `renameCharacterGroup(id, name, description)` updates the roster label.
- `deleteCharacterGroup(id)` deletes only the group and its memberships.
- `removeCharacterFromCharacterGroup(groupId, characterId)` detaches one character.

### Starting a story from a roster

New Roleplay and Create Group Chat both show **Start from a saved roster** above the character list. Each roster chip shows the roster name, its first member names, and how many members are chosen out of the total.

- Clicking a roster that is not fully selected adds every missing member in saved order.
- Clicking a fully selected roster removes its members.
- A roster that the player partly changed shows `chosen/total` and a dashed border. Clicking it completes the roster again.
- Rosters that share members add each character once.
- Group chat stops at ten characters and reports how many members were added.

Selecting a roster is a shortcut, not a lock. The player can still add or remove individual characters, and the story is created from the resulting list. The roleplay form also passes its description to the server, so the opening map and cast follow the premise.

### Group chat pacing

A new group chat or roleplay starts with a small opening cast chosen from the roster. The selector weighs three signals: how strongly each member's role and text match the scenario, how strongly the group is connected by recorded feelings, and whether the group covers different kinds of expertise. A character named in the scenario is always required in the opening cast. The story template's title, genre, description, suggested archetypes, and default world state join the scenario signal when one is present. The rest of the roster stays listed but cannot speak until the story introduces them. Each user action reveals at most one additional member. The backend filters character selection by this onstage flag, so an offstage member never speaks and other characters are told that the member is not present.

The first roster members are marked as a provisional cast during creation. The selector replaces that choice in a transaction; if selection fails, the provisional cast remains, so a scene always opens with someone able to speak.

A member who enters the scene but has not spoken yet takes the next turn, so an entrance lands as a beat instead of waiting behind the rotating cast. An approved character interjection also puts that character onstage when the player requests them. Every newcomer is placed at the current stage before speaking; see `docs/world-and-local-maps.md`.

Each user action also allows a bounded number of character turns, controlled by `maxConsecutiveResponses` (default three). A typed message and a manual continue both reset the budget and may reveal one offstage member. After the budget is spent, the conversation returns to `WAITING_FOR_USER` instead of letting every character speak in sequence. The scene direction tells each speaker to react to the most recent message, address at most one other character, and leave the rest of the cast room to answer.
