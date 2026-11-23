package com.eonmux.chatllm.services;

import com.eonmux.chatllm.agents.AgentContext;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.agents.AgentResult;
import com.eonmux.chatllm.agents.BattleMasterAgent;
import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.models.Battle;
import com.eonmux.chatllm.models.BattleVote;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class BattleService {

    @Inject
    Vertx vertx;

    @Inject
    Pool pool;

    @Inject
    AppConfig config;

    @Inject
    AgentModelConfigService modelConfigService;

    @Inject InventoryService inventoryService;
    @Inject StoryMessageService storyMessageService;

    private BattleMasterAgent battleMasterAgent;

    public Uni<Boolean> canManage(String conversationId, String userId) {
        return pool.preparedQuery("""
            SELECT 1 FROM conversation c WHERE c.conversationGUID = ? AND (c.ownerId = ? OR EXISTS
            (SELECT 1 FROM conversation_participant p WHERE p.conversation_id = c.conversationGUID
             AND p.user_id = ? AND p.is_active = TRUE AND LOWER(p.role) IN ('owner','admin')))
            """).execute(Tuple.of(conversationId, userId, userId)).map(rows -> rows.size() > 0);
    }

    public Uni<Boolean> canResolve(Battle battle, String userId) {
        return canManage(battle.getConversationId().toString(), userId).chain(manager -> {
            if (manager) return Uni.createFrom().item(true);
            return pool.preparedQuery("""
                SELECT COUNT(*) AS missing FROM (
                    SELECT ownerId AS userId FROM conversation WHERE conversationGUID = ?
                    UNION SELECT user_id AS userId FROM conversation_participant WHERE conversation_id = ? AND is_active = TRUE
                ) party WHERE NOT EXISTS (SELECT 1 FROM battle_vote v
                    WHERE v.battleId = ? AND v.roundNumber = ? AND v.userId = party.userId)
                """).execute(Tuple.of(battle.getConversationId().toString(), battle.getConversationId().toString(),
                    battle.getBattleId().toString(), battle.getRoundNumber()))
                .map(rows -> rows.iterator().next().getLong("missing") == 0);
        });
    }

    public Uni<Void> requirePeace(String conversationId) {
        return getActiveBattle(conversationId).chain(battle -> battle == null
            ? Uni.createFrom().voidItem()
            : Uni.createFrom().failure(new IllegalStateException("Resolve the active encounter before moving on. Retreat through the battle actions.")));
    }


    @PostConstruct
    void init() {
        this.battleMasterAgent = new BattleMasterAgent(vertx, pool, config, modelConfigService);
    }

    public Uni<Battle> initiateBattle(String conversationId, String userId, String enemyName,
                                       String enemyDescription, Double enemyHp, String narrativeContext) {
        if (enemyName == null || enemyName.isBlank() || enemyName.length() > 255
                || enemyHp == null || !Double.isFinite(enemyHp) || enemyHp <= 0 || enemyHp > 1000000)
            return Uni.createFrom().failure(new IllegalArgumentException("Provide an enemy name and positive, finite HP"));
        String battleId = UUID.randomUUID().toString();

        JsonObject battleData = new JsonObject()
            .put("enemyName", enemyName)
            .put("enemyDescription", enemyDescription)
            .put("enemyHp", enemyHp)
            .put("enemyMaxHp", enemyHp)
            .put("narrativeContext", narrativeContext);

        String insert = """
            INSERT INTO battle (battleId, conversationId, status, roundNumber, currentNarration, actionOptions, battleData)
            VALUES (?, ?, 'ACTIVE', 0, '', ?, ?)
            """;

        return pool.withTransaction(db -> db.preparedQuery("SELECT conversationGUID FROM conversation WHERE conversationGUID = ? FOR UPDATE")
            .execute(Tuple.of(conversationId))
            .chain(rows -> db.preparedQuery("SELECT battleId FROM battle WHERE conversationId = ? AND status = 'ACTIVE'")
                .execute(Tuple.of(conversationId)))
            .chain(rows -> {
                if (rows.size() > 0) return Uni.createFrom().failure(new IllegalStateException("A battle is already active"));
                return db.preparedQuery(insert).execute(Tuple.of(battleId, conversationId,
                    new JsonObject().put("actions", new JsonArray()).encode(), battleData.encode()));
            }))
            .chain(v -> generateRound(battleId, conversationId, null)
                .onFailure().call(error -> pool.preparedQuery("DELETE FROM battle WHERE battleId = ? AND roundNumber = 0")
                    .execute(Tuple.of(battleId)).invoke(deleted -> {
                        if (deleted.rowCount() > 0) vertx.eventBus().publish("rp.battle.ended." + conversationId,
                            JsonObject.mapFrom(Battle.builder().battleId(UUID.fromString(battleId)).conversationId(UUID.fromString(conversationId))
                                .status("FAILED").roundNumber(0).currentNarration("Encounter setup failed; try again.").build()));
                    }).replaceWithVoid()))
            .chain(v -> getBattle(battleId))
            .invoke(battle -> vertx.eventBus().publish("rp.battle.started." + conversationId, JsonObject.mapFrom(battle)));
    }

    public Uni<Void> generateRound(String battleId, String conversationId, JsonObject chosenAction) {
        return generateRound(battleId, conversationId, chosenAction, null);
    }

    private Uni<Void> generateRound(String battleId, String conversationId, JsonObject chosenAction, Integer expectedRound) {
        return getBattle(battleId)
            .chain(battle -> {
                if (!"ACTIVE".equals(battle.getStatus()) || (expectedRound != null && !expectedRound.equals(battle.getRoundNumber())))
                    return Uni.createFrom().failure(new IllegalStateException("This battle round has already been resolved"));
                AgentContext context = buildBattleContext(battle, chosenAction);
                return populatePartyState(context).chain(battleMasterAgent::run)
                    .chain(result -> {
                        if (!"success".equals(result.getStatus()) || result.getStructuredOutput() == null) {
                            log.warn("[battleService] BattleMaster agent returned non-success status for battle {}", battleId);
                            return Uni.createFrom().failure(
                                new RuntimeException("BattleMaster agent failed to generate round"));
                        }

                        JsonObject output = result.getStructuredOutput();
                        String narration = output.getString("narration", "");
                        if (narration.isBlank()) throw new IllegalArgumentException("Battle narration is empty");
                        JsonArray actions = output.getJsonArray("actions", new JsonArray());
                        JsonObject battleState = output.getJsonObject("battleState", new JsonObject());
                        boolean battleEnded = output.getBoolean("battleEnded", false);
                        String endStatus = output.getString("endStatus");
                        JsonArray statChanges = output.getJsonArray("statChanges", new JsonArray());
                        JsonArray inventoryChanges = output.getJsonArray("inventoryChanges", new JsonArray());

                        String battleDataStr = battle.getBattleData();
                        JsonObject battleDataObj;
                        try {
                            battleDataObj = (battleDataStr != null && !battleDataStr.isEmpty())
                                ? new JsonObject(battleDataStr) : new JsonObject();
                        } catch (Exception e) {
                            battleDataObj = new JsonObject();
                        }
                        if (battleState.containsKey("enemyHp") && battle.getRoundNumber() > 0) {
                            Double hp = battleState.getDouble("enemyHp");
                            if (hp == null || !Double.isFinite(hp)) throw new IllegalArgumentException("Invalid enemy health");
                            double maxHp = battleDataObj.getDouble("enemyMaxHp", battleDataObj.getDouble("enemyHp", Math.max(1, hp)));
                            battleDataObj.put("enemyHp", Math.max(0, Math.min(maxHp, hp)));
                        }
                        if (!battleEnded && actions.isEmpty()) throw new IllegalArgumentException("An active round must offer actions");
                        for (int option = 0; option < actions.size(); option++) {
                            JsonObject action = actions.getJsonObject(option);
                            if (action.getString("title", "").isBlank() || action.getString("description", "").isBlank())
                                throw new IllegalArgumentException("Incomplete battle action");
                        }
                        if (chosenAction != null) battleDataObj.put("chosenAction", chosenAction);
                        battleDataObj.put("partyStatus", battleState.getString("partyStatus"));
                        battleDataObj.put("roundOutcome", battleState.getString("roundOutcome"));
                        battleDataObj.put("enemyName",
                            battleDataObj.getString("enemyName", "Unknown Enemy"));
                        battleDataObj.put("enemyDescription",
                            battleDataObj.getString("enemyDescription", ""));

                        // Only this round's deltas may be applied; replaying accumulated loot duplicates grants.
                        battleDataObj.put("statChanges", statChanges);
                        battleDataObj.put("inventoryChanges", inventoryChanges);

                        if (battle.getRoundNumber() == 0 && (!statChanges.isEmpty() || !inventoryChanges.isEmpty() || battleEnded))
                            throw new IllegalArgumentException("The opening establishes the encounter; no action has been chosen yet");
                        if (!battleEnded && battleDataObj.getDouble("enemyHp", 1.0) <= 0)
                            throw new IllegalArgumentException("A defeated opponent cannot continue an active battle");
                        if (!battleEnded && actions.stream().map(value -> (JsonObject) value).noneMatch(action -> "retreat".equals(action.getString("type"))))
                            actions.add(new JsonObject().put("title", "Disengage").put("description", "Attempt to withdraw from this confrontation, accepting the risks of retreat.").put("type", "retreat"));
                        if (battleEnded && (endStatus == null || !java.util.Set.of("VICTORY", "DEFEAT", "RETREAT").contains(endStatus)))
                            throw new IllegalArgumentException("Battle ended without a valid outcome");
                        if (battleEnded) battleDataObj.put("endStatus", endStatus);

                        int newRoundNumber = battle.getRoundNumber() + 1;

                        String actionOptionsStr = new JsonObject().put("actions", actions).encode();

                        String update = """
                            UPDATE battle SET roundNumber = ?, currentNarration = ?, actionOptions = ?, battleData = ?, status = ?, updatedAt = NOW()
                            WHERE battleId = ? AND roundNumber = ? AND status = 'ACTIVE'
                            """;

                        final String encodedBattleData = battleDataObj.encode();
                        final String roundNarration = narration + (battleEnded ? "\n\nOutcome: " + endStatus.toLowerCase(java.util.Locale.ROOT) + "." : "");
                        return pool.withTransaction(db -> db.preparedQuery("SELECT conversationGUID FROM conversation WHERE conversationGUID = ? FOR UPDATE")
                            .execute(Tuple.of(conversationId))
                            .chain(() -> db.preparedQuery(update).execute(Tuple.tuple()
                                .addValue(newRoundNumber).addValue(narration).addValue(actionOptionsStr)
                                .addValue(encodedBattleData).addValue(battleEnded ? endStatus : "ACTIVE")
                                .addValue(battleId).addValue(battle.getRoundNumber())))
                            .invoke(rows -> { if (rows.rowCount() != 1) throw new IllegalStateException("This battle round has already been resolved"); })
                            .chain(() -> applyStatChanges(db, conversationId, statChanges))
                            .chain(() -> inventoryService.applyBattleChanges(db, conversationId, inventoryChanges))
                            .chain(() -> storyMessageService.persist(db, conversationId,
                                "battle:" + battleId + ":round:" + newRoundNumber, roundNarration, battleId)))
                            .invoke(messageId -> storyMessageService.publish(conversationId, messageId, roundNarration, battleId))
                            .chain(() -> getBattle(battleId)).invoke(updated -> {
                                vertx.eventBus().publish("rp.battle.round." + conversationId, JsonObject.mapFrom(updated));
                                if (!statChanges.isEmpty() || !inventoryChanges.isEmpty()) vertx.eventBus().publish(
                                    com.eonmux.chatllm.config.RoleplayEventAddresses.INVENTORY_CHANGED + conversationId,
                                    new JsonObject().put("conversationId", conversationId).put("inventoryId", battleId));
                                if (battleEnded) vertx.eventBus().publish("rp.battle.ended." + conversationId, JsonObject.mapFrom(updated));
                            }).replaceWithVoid();
                    });
            })
            .replaceWithVoid();
    }

    public Uni<BattleVote> castVote(String battleId, String userId, int optionIndex) {
        return castVote(battleId, userId, optionIndex, null);
    }

    public Uni<BattleVote> castVote(String battleId, String userId, int optionIndex, Integer expectedRound) {
        return pool.withTransaction(db -> db.preparedQuery("SELECT * FROM battle WHERE battleId = ? FOR UPDATE")
            .execute(Tuple.of(battleId)).chain(rows -> {
                if (rows.size() == 0) return Uni.createFrom().failure(new IllegalArgumentException("Battle not found"));
                Battle battle = mapRowToBattle(rows.iterator().next());
                if (!"ACTIVE".equals(battle.getStatus()) || (expectedRound != null && !expectedRound.equals(battle.getRoundNumber())))
                    return Uni.createFrom().failure(new IllegalStateException("The battle has moved on. Refresh before voting."));
                JsonArray options = new JsonObject(battle.getActionOptions()).getJsonArray("actions", new JsonArray());
                if (optionIndex < 0 || optionIndex >= options.size())
                    return Uni.createFrom().failure(new IllegalArgumentException("Invalid battle action"));
                String voteId = UUID.randomUUID().toString();
                return db.preparedQuery("""
                    INSERT INTO battle_vote (id, battleId, roundNumber, userId, optionIndex) VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE optionIndex = VALUES(optionIndex)
                    """).execute(Tuple.of(voteId, battleId, battle.getRoundNumber(), userId, optionIndex))
                    .chain(() -> db.preparedQuery("SELECT * FROM battle_vote WHERE battleId = ? AND roundNumber = ? AND userId = ?")
                        .execute(Tuple.of(battleId, battle.getRoundNumber(), userId)))
                    .map(saved -> mapRowToVote(saved.iterator().next()));
            })).call(vote -> getBattle(battleId).invoke(battle -> vertx.eventBus().publish(
                "rp.battle.vote." + battle.getConversationId(), JsonObject.mapFrom(vote))).replaceWithVoid());
    }


    public Uni<Battle> proposeAction(String battleId, int roundNumber, String description) {
        if (description == null || description.isBlank() || description.trim().length() > 600)
            return Uni.createFrom().failure(new IllegalArgumentException("Describe an approach in 1–600 characters"));
        return pool.withTransaction(db -> db.preparedQuery("SELECT * FROM battle WHERE battleId = ? FOR UPDATE")
            .execute(Tuple.of(battleId)).chain(rows -> {
                if (rows.size() == 0) return Uni.createFrom().failure(new IllegalArgumentException("Battle not found"));
                Battle battle = mapRowToBattle(rows.iterator().next());
                if (!"ACTIVE".equals(battle.getStatus()) || battle.getRoundNumber() != roundNumber)
                    return Uni.createFrom().failure(new IllegalStateException("This round has moved on"));
                JsonObject options = new JsonObject(battle.getActionOptions());
                JsonArray actions = options.getJsonArray("actions", new JsonArray());
                if (actions.size() >= 6) return Uni.createFrom().failure(new IllegalArgumentException("This round already has six approaches"));
                actions.add(new JsonObject().put("title", "Party proposal").put("description", description.trim()).put("type", "special"));
                return db.preparedQuery("UPDATE battle SET actionOptions = ?, updatedAt = NOW() WHERE battleId = ?")
                    .execute(Tuple.of(options.encode(), battleId)).replaceWith(battle);
            })).chain(battle -> getBattle(battleId))
            .invoke(battle -> vertx.eventBus().publish("rp.battle.round." + battle.getConversationId(), JsonObject.mapFrom(battle)));
    }

    public Uni<Void> resolveRound(String battleId, String conversationId) {
        return resolveRound(battleId, conversationId, null);
    }

    public Uni<Void> resolveRound(String battleId, String conversationId, Integer expectedRound) {
        return getBattle(battleId)
            .invoke(battle -> {
                if (!"ACTIVE".equals(battle.getStatus()) || (expectedRound != null && !expectedRound.equals(battle.getRoundNumber())))
                    throw new IllegalStateException("This battle round has already advanced");
            })
            .chain(battle -> getVotes(battleId, battle.getRoundNumber())
                .chain(votes -> {
                    if (votes.isEmpty()) {
                        log.warn("[battleService] No votes for round {} of battle {}", battle.getRoundNumber(), battleId);
                        return Uni.createFrom().failure(new IllegalStateException("No votes for the current battle round"));
                    }

                    int winningIndex = determineWinningOption(votes);

                    String actionOptionsStr = battle.getActionOptions();
                    String chosenActionTitle = "Option " + (winningIndex + 1);
                    JsonObject chosenActionObj = null;
                    if (actionOptionsStr != null && !actionOptionsStr.isEmpty()) {
                        try {
                            JsonObject actionOptionsObj = new JsonObject(actionOptionsStr);
                            JsonArray actions = actionOptionsObj.getJsonArray("actions", new JsonArray());
                            if (winningIndex >= 0 && winningIndex < actions.size()) {
                                chosenActionObj = actions.getJsonObject(winningIndex);
                                chosenActionTitle = chosenActionObj.getString("title", chosenActionTitle);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse actionOptions for battle {}: {}", battle.getBattleId(), e.getMessage());
                        }
                    }

                    if (chosenActionObj == null) return Uni.createFrom().failure(new IllegalStateException("Winning action is no longer valid"));
                    vertx.eventBus().publish("rp.battle.resolved." + conversationId,
                        new JsonObject()
                            .put("battleId", battleId)
                            .put("roundNumber", battle.getRoundNumber())
                            .put("winningOption", winningIndex)
                            .put("chosenAction", chosenActionTitle));

                    log.info("[battleService] Round {} resolved for battle {}, winning option: {} ({})",
                        battle.getRoundNumber(), battleId, winningIndex, chosenActionTitle);

                    return generateRound(battleId, conversationId, chosenActionObj, battle.getRoundNumber());
                }));
    }

    public Uni<Battle> getBattle(String battleId) {
        String query = "SELECT * FROM battle WHERE battleId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(battleId))
            .map(rows -> {
                if (rows.size() > 0) {
                    return mapRowToBattle(rows.iterator().next());
                }
                throw new RuntimeException("Battle not found: " + battleId);
            });
    }

    public Uni<Battle> getActiveBattle(String conversationId) {
        String query = "SELECT * FROM battle WHERE conversationId = ? AND status = 'ACTIVE' LIMIT 1";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> rows.size() > 0 ? mapRowToBattle(rows.iterator().next()) : null);
    }

    public Uni<List<BattleVote>> getVotes(String battleId, int roundNumber) {
        String query = """
            SELECT v.* FROM battle_vote v JOIN battle b ON b.battleId = v.battleId JOIN conversation c ON c.conversationGUID = b.conversationId
            WHERE v.battleId = ? AND v.roundNumber = ? AND (v.userId = c.ownerId OR EXISTS
                (SELECT 1 FROM conversation_participant p WHERE p.conversation_id = c.conversationGUID AND p.user_id = v.userId AND p.is_active = TRUE))
            """;
        return pool.preparedQuery(query)
            .execute(Tuple.of(battleId, roundNumber))
            .map(rows -> {
                List<BattleVote> votes = new ArrayList<>();
                for (Row row : rows) votes.add(mapRowToVote(row));
                return votes;
            });
    }

    public Uni<Void> endBattle(String battleId, String status) {
        if (status == null || !java.util.Set.of("VICTORY", "DEFEAT", "RETREAT").contains(status))
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid battle outcome"));
        return getBattle(battleId).chain(battle -> {
            String conversationId = battle.getConversationId().toString();
            String narration = "The encounter ended in " + status.toLowerCase(java.util.Locale.ROOT) + ".";
            return pool.withTransaction(db -> db.preparedQuery("SELECT conversationGUID FROM conversation WHERE conversationGUID = ? FOR UPDATE")
                .execute(Tuple.of(conversationId)).chain(() -> db.preparedQuery("UPDATE battle SET status = ?, currentNarration = ?, battleData = JSON_SET(COALESCE(battleData, JSON_OBJECT()), '$.endStatus', ?), updatedAt = NOW() WHERE battleId = ? AND status = 'ACTIVE'")
                    .execute(Tuple.of(status, narration, status, battleId)))
                .invoke(rows -> { if (rows.rowCount() != 1) throw new IllegalStateException("Battle has already ended"); })
                .chain(() -> storyMessageService.persist(db, conversationId, "battle:" + battleId + ":end", narration, battleId)))
                .invoke(messageId -> storyMessageService.publish(conversationId, messageId, narration, battleId))
                .chain(() -> getBattle(battleId)).invoke(updated -> vertx.eventBus().publish("rp.battle.ended." + conversationId, JsonObject.mapFrom(updated)))
                .replaceWithVoid();
        });
    }

    public Uni<List<Battle>> getBattlesByConversation(String conversationId) {
        String query = "SELECT * FROM battle WHERE conversationId = ? ORDER BY (status = 'ACTIVE') DESC, createdAt DESC LIMIT 50";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                List<Battle> battles = new ArrayList<>();
                for (Row row : rows) battles.add(mapRowToBattle(row));
                return battles;
            });
    }

    int determineWinningOption(List<BattleVote> votes) {
        java.util.Map<Integer, Integer> voteCounts = new java.util.HashMap<>();
        for (BattleVote vote : votes) {
            voteCounts.merge(vote.getOptionIndex(), 1, Integer::sum);
        }
        return voteCounts.entrySet().stream()
            .sorted(java.util.Comparator.<java.util.Map.Entry<Integer, Integer>>comparingInt(java.util.Map.Entry::getValue)
                .reversed().thenComparingInt(java.util.Map.Entry::getKey)).findFirst()
            .map(java.util.Map.Entry::getKey)
            .orElse(0);
    }

    Uni<AgentContext> populatePartyState(AgentContext context) {
        String conversation = context.getConversationId();
        var inventory = pool.preparedQuery("SELECT * FROM character_inventory WHERE conversationId = ?")
            .execute(Tuple.of(conversation)).map(rows -> ServiceHelper.mapRowsToJsonArray(rows,
                "inventoryId", "characterId", "userId", "itemName", "itemDescription", "quantity", "category", "properties", "isEquipped"));
        var stats = pool.preparedQuery("SELECT * FROM character_stat WHERE conversationId = ?")
            .execute(Tuple.of(conversation)).map(rows -> ServiceHelper.mapRowsToJsonArray(rows, "characterId", "statName", "statValue", "statMax"));
        var roster = pool.preparedQuery("""
            SELECT ch.characterUUID AS characterId, ch.name FROM `character` ch JOIN conversation c
            ON c.conversationGUID = ? WHERE ch.characterUUID = c.characterId OR EXISTS
            (SELECT 1 FROM conversation_character cc WHERE cc.conversationId = c.conversationGUID
             AND cc.characterId = ch.characterUUID AND cc.isActive = TRUE AND COALESCE(cc.isIntroduced, TRUE) = TRUE)
            """).execute(Tuple.of(conversation)).map(rows -> ServiceHelper.mapRowsToJsonArray(rows, "characterId", "name"));
        var story = pool.preparedQuery("SELECT content, displayName, role FROM message WHERE conversationId = ? AND isDeleted = FALSE ORDER BY seq DESC LIMIT 12")
            .execute(Tuple.of(conversation)).map(rows -> {
                java.util.List<JsonObject> messages = new ArrayList<>();
                rows.forEach(row -> messages.add(new JsonObject().put("content", row.getString("content"))
                    .put("displayName", row.getString("displayName")).put("role", row.getString("role"))));
                java.util.Collections.reverse(messages); return new JsonArray(messages);
            });
        var setting = pool.preparedQuery("SELECT c.description, ws.currentLocation, ws.currentArea, ws.currentScene, ws.timeOfDay, ws.weather, t.rules FROM conversation c LEFT JOIN world_state ws ON ws.conversationId = c.conversationGUID LEFT JOIN story_template t ON t.templateId = COALESCE(ws.templateId, c.storyTemplateId) WHERE c.conversationGUID = ?")
            .execute(Tuple.of(conversation)).map(rows -> rows.size() == 0 ? new JsonObject()
                : ServiceHelper.mapRowsToJsonArray(rows, "description", "currentLocation", "currentArea", "currentScene", "timeOfDay", "weather", "rules").getJsonObject(0));
        return Uni.combine().all().unis(inventory, stats, roster, story, setting).with(state -> {
            var stateInventory = (JsonArray) state.get(0);
            var stateStats = (JsonArray) state.get(1);
            var stateRoster = (JsonArray) state.get(2);
            var stateStory = (JsonArray) state.get(3);
            var stateSetting = (JsonObject) state.get(4);
            JsonObject sheet = context.getCharacterSheet() == null ? new JsonObject() : context.getCharacterSheet();
            context.setCharacterSheet(sheet.put("partyInventory", stateInventory).put("partyStats", stateStats).put("partyMembers", stateRoster));
            context.setRecentMessages(stateStory);
            context.setRoleplayDescription(stateSetting.getString("description"));
            sheet.put("world", stateSetting);
            return context;
        });
    }

    private Uni<Void> applyStatChanges(io.vertx.mutiny.sqlclient.SqlClient db, String conversationId, JsonArray changes) {
        Uni<Void> sequence = Uni.createFrom().voidItem();
        for (int index = 0; index < changes.size(); index++) {
            JsonObject change = changes.getJsonObject(index);
            String character = change.getString("characterId"), stat = change.getString("statName");
            Double delta = change.getDouble("delta");
            if (character == null || stat == null || delta == null || !Double.isFinite(delta))
                throw new IllegalArgumentException("Invalid battle stat change");
            sequence = sequence.chain(() -> db.preparedQuery("""
                SELECT s.statId FROM character_stat s JOIN conversation c ON c.conversationGUID = s.conversationId
                WHERE s.conversationId = ? AND s.characterId = ? AND s.statName = ? AND s.statType = 'numeric'
                AND (c.characterId = s.characterId OR EXISTS (SELECT 1 FROM conversation_character cc
                     WHERE cc.conversationId = c.conversationGUID AND cc.characterId = s.characterId AND cc.isActive = TRUE)) FOR UPDATE
                """).execute(Tuple.of(conversationId, character, stat))
                .invoke(rows -> { if (rows.size() == 0) throw new IllegalArgumentException("Battle may only change existing active character stats"); })
                .chain(() -> db.preparedQuery("""
                    UPDATE character_stat SET statValue = GREATEST(0, LEAST(COALESCE(statMax, 1.7976931348623157e308), statValue + ?))
                    WHERE conversationId = ? AND characterId = ? AND statName = ?
                    """).execute(Tuple.of(delta, conversationId, character, stat))).replaceWithVoid());
        }
        return sequence;
    }

    private AgentContext buildBattleContext(Battle battle, JsonObject chosenAction) {
        AgentContext.AgentContextBuilder builder = AgentContext.builder()
            .conversationId(battle.getConversationId().toString())
            .triggerContent(buildBattlePrompt(battle, chosenAction))
            .battleContext(new JsonObject().put("roundNumber", battle.getRoundNumber()).put("chosenAction", chosenAction));

        String battleDataStr = battle.getBattleData();
        if (battleDataStr != null && !battleDataStr.isEmpty()) {
            try {
                JsonObject battleDataObj = new JsonObject(battleDataStr);
                JsonObject characterSheet = new JsonObject();
                characterSheet.put("enemyName", battleDataObj.getString("enemyName", "Unknown"));
                characterSheet.put("enemyDescription", battleDataObj.getString("enemyDescription", ""));
                characterSheet.put("enemyHp", battleDataObj.getDouble("enemyHp", 0.0));
                characterSheet.put("partyStatus", battleDataObj.getString("partyStatus", "Unknown"));
                characterSheet.put("roundOutcome", battleDataObj.getString("roundOutcome", ""));
                characterSheet.put("narrativeContext", battleDataObj.getString("narrativeContext", ""));
                builder.characterSheet(characterSheet);
            } catch (Exception e) {
                log.warn("Failed to parse battleData for battle {}: {}", battle.getBattleId(), e.getMessage());
            }
        }

        return builder.build();
    }

    private String buildBattlePrompt(Battle battle, JsonObject chosenAction) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Round Number: ").append(battle.getRoundNumber() + 1).append("\n");

        if (chosenAction != null) {
            prompt.append("Chosen Action: ").append(chosenAction.getString("title", "Unknown")).append("\n");
            prompt.append("Action Description: ").append(chosenAction.getString("description", "")).append("\n");
            prompt.append("Action Type: ").append(chosenAction.getString("type", "attack")).append("\n");
        }

        String battleDataStr = battle.getBattleData();
        if (battleDataStr != null && !battleDataStr.isEmpty()) {
            try {
                JsonObject battleDataObj = new JsonObject(battleDataStr);
                prompt.append("Enemy: ").append(battleDataObj.getString("enemyName", "Unknown")).append("\n");
                prompt.append("Enemy Description: ").append(battleDataObj.getString("enemyDescription", "")).append("\n");
                prompt.append("Enemy HP: ").append(battleDataObj.getDouble("enemyHp", 0.0)).append("\n");
                prompt.append("Party Status: ").append(battleDataObj.getString("partyStatus", "Ready")).append("\n");
                prompt.append("Context: ").append(battleDataObj.getString("narrativeContext", "")).append("\n");
            } catch (Exception e) {
                log.warn("Failed to parse battleData for prompt: {}", e.getMessage());
            }
        }
        if (battle.getCurrentNarration() != null && !battle.getCurrentNarration().isEmpty()) {
            prompt.append("Previous Narration: ").append(battle.getCurrentNarration()).append("\n");
        }
        return prompt.toString();
    }

    private Battle mapRowToBattle(Row row) {
        String battleIdStr = row.getString("battleId");
        String convIdStr = row.getString("conversationId");
        return Battle.builder()
            .battleId(battleIdStr != null ? UUID.fromString(battleIdStr) : null)
            .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
            .status(row.getString("status"))
            .roundNumber(row.getInteger("roundNumber"))
            .currentNarration(row.getString("currentNarration"))
            .actionOptions(ServiceHelper.safeGetJsonText(row, "actionOptions"))
            .battleData(ServiceHelper.safeGetJsonText(row, "battleData"))
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
            .build();
    }

    private BattleVote mapRowToVote(Row row) {
        String idStr = row.getString("id");
        String battleIdStr = row.getString("battleId");
        String userIdStr = row.getString("userId");
        return BattleVote.builder()
            .id(idStr != null ? UUID.fromString(idStr) : null)
            .battleId(battleIdStr != null ? UUID.fromString(battleIdStr) : null)
            .roundNumber(row.getInteger("roundNumber"))
            .userId(userIdStr != null ? UUID.fromString(userIdStr) : null)
            .optionIndex(row.getInteger("optionIndex"))
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .build();
    }
}
