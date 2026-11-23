package com.eonmux.chatllm.services;

import com.eonmux.chatllm.models.CharacterInventory;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.mutiny.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in with RUN_INVENTORY_DB_TESTS=true; runs on an isolated disposable MySQL. */
@EnabledIfEnvironmentVariable(named = "RUN_INVENTORY_DB_TESTS", matches = "true")
class InventoryServiceDatabaseTest {
    static GenericContainer<?> mysql;
    static Vertx vertx;
    static InventoryService service;
    static ExecutorService workers;
    String conversation, host, player, other, outsider, character;

    @BeforeAll static void start() throws Exception {
        workers = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setContextClassLoader(InventoryServiceDatabaseTest.class.getClassLoader());
            return thread;
        });
        mysql = new GenericContainer<>("mysql:8.0").withEnv("MYSQL_ROOT_PASSWORD", "inventory-test")
            .withEnv("MYSQL_DATABASE", "inventory_test").withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*port: 3306  MySQL Community Server.*\\n", 1).withStartupTimeout(Duration.ofMinutes(2)));
        mysql.start();
        vertx = Vertx.vertx();
        service = new InventoryService();
        service.vertx = vertx;
        service.pool = MySQLPool.pool(vertx, new MySQLConnectOptions().setHost(mysql.getHost()).setPort(mysql.getMappedPort(3306))
            .setDatabase("inventory_test").setUser("root").setPassword("inventory-test"), new PoolOptions().setMaxSize(12));
        sql("CREATE TABLE user (userUUID VARCHAR(36) PRIMARY KEY, username VARCHAR(100)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        String schema = new String(InventoryServiceDatabaseTest.class.getResourceAsStream("/db/schema.sql").readAllBytes(), StandardCharsets.UTF_8);
        for (String name : List.of("`character`", "conversation", "conversation_participant", "conversation_character", "character_inventory", "character_stat", "inventory_activity", "story_template", "world_state", "message", "battle", "battle_vote", "scene", "scene_character")) {
            var match = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?" + name + " \\(.*?ENGINE=InnoDB[^;]*;", Pattern.DOTALL).matcher(schema);
            assertTrue(match.find(), name);
            sql(match.group());
        }
        sql("ALTER TABLE `character` ADD COLUMN gender VARCHAR(100), ADD COLUMN pronouns VARCHAR(40), ADD COLUMN ageYears INT");
    }
    @AfterAll static void stop() { if (workers != null) workers.shutdownNow(); if (service != null && service.pool != null) service.pool.closeAndAwait(); if (vertx != null) vertx.closeAndAwait(); if (mysql != null) mysql.stop(); }
    static String id() { return UUID.randomUUID().toString(); }
    static void sql(String query, Object... args) { service.pool.preparedQuery(query).execute(Tuple.from(Arrays.asList(args))).await().indefinitely(); }
    @BeforeEach void fixture() {
        conversation = id(); host = id(); player = id(); other = id(); outsider = id(); character = id();
        for (String user : List.of(host, player, other, outsider)) sql("INSERT INTO user VALUES (?, ?)", user, user);
        sql("INSERT INTO `character` (characterUUID,name,ownerId) VALUES (?, 'Elara', ?)", character, host);
        sql("INSERT INTO conversation (conversationGUID, ownerId) VALUES (?, ?)", conversation, host);
        for (String user : List.of(player, other)) sql("INSERT INTO conversation_participant (id, conversation_id, user_id) VALUES (?, ?, ?)", id(), conversation, user);
        sql("INSERT INTO conversation_character (id, conversationId, characterId) VALUES (?, ?, ?)", id(), conversation, character);
    }
    CharacterInventory give(String owner, String name, int quantity, String category, JsonObject properties) {
        return service.add(conversation, null, owner, name, null, quantity, category, properties, false, host).await().indefinitely();
    }
    CharacterInventory potion(int quantity, JsonObject properties) {
        return service.add(conversation, character, null, "Healing potion", null, quantity, "consumable", properties, false, host).await().indefinitely();
    }
    @Test void playersCannotMintDeleteOrStealEachOthersItems() {
        var item = give(player, "Potion", 2, "consumable", null);
        String itemId = item.getInventoryId().toString();
        assertThrows(SecurityException.class, () -> service.add(conversation, null, player, "Gold", null, 100, "misc", null, false, player).await().indefinitely());
        assertThrows(SecurityException.class, () -> service.removeItem(itemId, player).await().indefinitely());
        assertThrows(SecurityException.class, () -> service.useItem(itemId, other).await().indefinitely());
        assertThrows(SecurityException.class, () -> service.transferItem(itemId, null, other, other).await().indefinitely());
        assertThrows(SecurityException.class, () -> service.equipItem(itemId, true, outsider).await().indefinitely());
        assertEquals(2, service.getItem(itemId).await().indefinitely().getQuantity());
        assertFalse(service.access(conversation, null, player, other).await().indefinitely().getCanModify());
        assertThrows(SecurityException.class, () -> service.access(conversation, null, player, outsider).await().indefinitely());
    }
    @Test void transferValidatesMembershipAndClearsEquipment() {
        var item = give(player, "Sword", 1, "weapon", null);
        String itemId = item.getInventoryId().toString();
        service.equipItem(itemId, true, player).await().indefinitely();
        assertThrows(IllegalArgumentException.class, () -> service.transferItem(itemId, null, outsider, player).await().indefinitely());
        assertThrows(IllegalArgumentException.class, () -> service.transferItem(itemId, character, other, player).await().indefinitely());
        var transferred = service.transferItem(itemId, null, other, player).await().indefinitely();
        assertEquals(other, transferred.getUserId().toString()); assertNull(transferred.getCharacterId()); assertFalse(transferred.getIsEquipped());
        assertThrows(SecurityException.class, () -> service.equipItem(itemId, true, player).await().indefinitely());
    }
    @Test void concurrentConsumptionOfLastUnitAppliesEffectsOnce() throws Exception {
        sql("INSERT INTO character_stat (statId, conversationId, characterId, statName, statValue, statMax) VALUES (?, ?, ?, 'HP', 5, 20)", id(), conversation, character);
        var item = potion(1, new JsonObject().put("effects", new JsonArray().add(new JsonObject().put("statName", "HP").put("delta", 10))));
        var start = new CountDownLatch(1);
        var attempts = new ArrayList<CompletableFuture<Boolean>>();
        for (int i = 0; i < 8; i++) attempts.add(CompletableFuture.supplyAsync(() -> {
            try { start.await(); service.useItem(item.getInventoryId().toString(), host).await().indefinitely(); return true; }
            catch (Exception expected) { return false; }
        }, workers));
        start.countDown();
        long successes = 0;
        for (var attempt : attempts) if (attempt.get(15, TimeUnit.SECONDS)) successes++;
        assertEquals(1, successes);
        assertNull(service.getItem(item.getInventoryId().toString()).await().indefinitely());
        assertEquals(15d, stat());
        var actions = service.recentActions(conversation).await().indefinitely();
        assertEquals(1, actions.stream().map(v -> (JsonObject) v).filter(a -> "use".equals(a.getString("action"))).count());
    }
    double stat() { return service.pool.preparedQuery("SELECT statValue FROM character_stat WHERE conversationId = ? AND statName = 'HP'").execute(Tuple.of(conversation)).await().indefinitely().iterator().next().getDouble("statValue"); }
    @Test void effectsClampAndFailuresRollBackConsumptionAndEarlierEffects() {
        sql("INSERT INTO character_stat (statId, conversationId, characterId, statName, statValue, statMax) VALUES (?, ?, ?, 'HP', 5, 20)", id(), conversation, character);
        var effects = new JsonArray().add(new JsonObject().put("statName", "HP").put("delta", 100));
        var item = potion(2, new JsonObject().put("effects", effects));
        assertEquals(1, service.useItem(item.getInventoryId().toString(), host).await().indefinitely().getQuantity());
        assertEquals(20d, stat());
        effects.add(new JsonObject().put("statName", "missing").put("delta", 5));
        var broken = service.add(conversation, character, null, "Broken potion", null, 1, "consumable", new JsonObject().put("effects", effects), false, host).await().indefinitely();
        sql("UPDATE character_stat SET statValue = 1 WHERE conversationId = ?", conversation);
        assertThrows(IllegalArgumentException.class, () -> service.useItem(broken.getInventoryId().toString(), host).await().indefinitely());
        assertEquals(1d, stat()); assertEquals(1, service.getItem(broken.getInventoryId().toString()).await().indefinitely().getQuantity());
    }
    @Test void concurrentGrantsStackWithoutLostUpdatesAndVariantsStayDistinct() throws Exception {
        var attempts = new ArrayList<CompletableFuture<CharacterInventory>>();
        for (int i = 0; i < 8; i++) attempts.add(CompletableFuture.supplyAsync(() -> give(player, "Coin", 1, "misc", null), workers));
        for (var attempt : attempts) attempt.get(15, TimeUnit.SECONDS);
        var inventory = service.getUserInventory(conversation, player).await().indefinitely();
        assertEquals(1, inventory.size()); assertEquals(8, inventory.getFirst().getQuantity());
        give(player, "Coin", 1, "misc", new JsonObject().put("enchantment", "fire"));
        assertEquals(2, service.getUserInventory(conversation, player).await().indefinitely().size());
        assertThrows(IllegalArgumentException.class, () -> service.removeByName(conversation, null, player, "Coin", 1).await().indefinitely());
    }
    @Test void partialTransfersConserveQuantityAndKeepSourceEquipment() {
        var item = give(player, "Throwing knife", 5, "weapon", null);
        String source = item.getInventoryId().toString();
        service.equipItem(source, true, player).await().indefinitely();
        var sent = service.transferItem(source, null, other, 2, player).await().indefinitely();
        assertNotEquals(item.getInventoryId(), sent.getInventoryId());
        assertEquals(2, sent.getQuantity()); assertFalse(sent.getIsEquipped());
        var remaining = service.getItem(source).await().indefinitely();
        assertEquals(3, remaining.getQuantity()); assertTrue(remaining.getIsEquipped());
        assertThrows(IllegalArgumentException.class, () -> service.transferItem(source, null, other, 4, player).await().indefinitely());
        assertEquals(3, service.getItem(source).await().indefinitely().getQuantity());
    }
    @Test void playerCanUsePotionOnAnAuthorizedCharacterWithoutTransferringItFirst() {
        sql("UPDATE `character` SET ownerId = ? WHERE characterUUID = ?", player, character);
        sql("INSERT INTO character_stat (statId, conversationId, characterId, statName, statValue, statMax) VALUES (?, ?, ?, 'HP', 5, 20)", id(), conversation, character);
        var item = give(player, "Potion", 1, "consumable", new JsonObject().put("effects", new JsonArray().add(new JsonObject().put("statName", "HP").put("delta", 10))));
        assertThrows(IllegalArgumentException.class, () -> service.useItem(item.getInventoryId().toString(), player).await().indefinitely());
        assertEquals(0, service.useItem(item.getInventoryId().toString(), player, character).await().indefinitely().getQuantity());
        assertEquals(15d, stat());
        var use = service.recentActions(conversation).await().indefinitely().getJsonObject(0);
        assertEquals(character, use.getJsonObject("details").getString("effectTargetCharacterId"));
    }
    @Test void cannotApplyEffectsToAnUnauthorizedCharacterOrAnOutsider() {
        var item = give(player, "Potion", 1, "consumable", new JsonObject().put("effects", new JsonArray().add(new JsonObject().put("statName", "HP").put("delta", 10))));
        assertThrows(SecurityException.class, () -> service.useItem(item.getInventoryId().toString(), player, character).await().indefinitely());
        assertThrows(IllegalArgumentException.class, () -> service.useItem(item.getInventoryId().toString(), player, id()).await().indefinitely());
        assertEquals(1, service.getItem(item.getInventoryId().toString()).await().indefinitely().getQuantity());
    }
    @Test void onlyConsumablesAreConsumedAndReusableItemsRemain() {
        var sword = give(player, "Sword", 1, "weapon", null);
        assertThrows(IllegalArgumentException.class, () -> service.useItem(sword.getInventoryId().toString(), player).await().indefinitely());
        var potion = give(player, "Potion", 2, "consumable", null);
        assertThrows(IllegalArgumentException.class, () -> service.equipItem(potion.getInventoryId().toString(), true, player).await().indefinitely());
        var tool = give(player, "Lantern", 1, "equipment", new JsonObject().put("usable", true));
        assertEquals(1, service.useItem(tool.getInventoryId().toString(), player).await().indefinitely().getQuantity());
    }
    @Test void explicitEquipmentSlotsExcludeAnotherItemInSameSlot() {
        var props = new JsonObject().put("slot", "head");
        var first = give(player, "Helmet", 1, "armor", props);
        var second = give(player, "Crown", 1, "armor", props);
        service.equipItem(first.getInventoryId().toString(), true, player).await().indefinitely();
        service.equipItem(second.getInventoryId().toString(), true, player).await().indefinitely();
        assertFalse(service.getItem(first.getInventoryId().toString()).await().indefinitely().getIsEquipped());
        assertTrue(service.getItem(second.getInventoryId().toString()).await().indefinitely().getIsEquipped());
    }
    BattleService battleService(JsonObject output) throws Exception {
        BattleService battles = new BattleService();
        battles.pool = service.pool; battles.vertx = vertx; battles.inventoryService = service;
        battles.storyMessageService = new StoryMessageService();
        battles.storyMessageService.pool = service.pool; battles.storyMessageService.vertx = vertx;
        var agent = org.mockito.Mockito.mock(com.eonmux.chatllm.agents.BattleMasterAgent.class);
        org.mockito.Mockito.when(agent.run(org.mockito.ArgumentMatchers.any())).thenReturn(io.smallrye.mutiny.Uni.createFrom()
            .item(com.eonmux.chatllm.agents.AgentResult.builder().status("success").structuredOutput(output).build()));
        var field = BattleService.class.getDeclaredField("battleMasterAgent"); field.setAccessible(true); field.set(battles, agent);
        return battles;
    }
    String battleFixture() {
        String battle = id();
        sql("INSERT INTO battle (battleId, conversationId, roundNumber, battleData, actionOptions) VALUES (?, ?, 1, ?, ?)",
            battle, conversation, new JsonObject().put("enemyName", "Road guards").put("enemyHp", 20).put("enemyMaxHp", 20).encode(),
            new JsonObject().put("actions", new JsonArray().add(new JsonObject().put("title", "Take cover").put("description", "Get behind the wall").put("type", "defend"))).encode());
        return battle;
    }
    JsonObject roundOutput() {
        return new JsonObject().put("narration", "The guards advanced while Elara held cover.")
            .put("actions", new JsonArray().add(new JsonObject().put("title", "Negotiate").put("description", "Offer a truce").put("type", "special")))
            .put("battleState", new JsonObject().put("enemyHp", 12)).put("battleEnded", false);
    }
    long count(String query, Object... args) {
        return service.pool.preparedQuery(query).execute(Tuple.from(Arrays.asList(args))).await().indefinitely().iterator().next().getLong("n");
    }
    @Test void battleConsequencesNarrationAndInventoryCommitTogetherExactlyOnce() throws Exception {
        sql("INSERT INTO character_stat (statId, conversationId, characterId, statName, statValue, statMax) VALUES (?, ?, ?, 'HP', 5, 20)", id(), conversation, character);
        var potion = potion(2, new JsonObject().put("effects", new JsonArray().add(new JsonObject().put("statName", "HP").put("delta", 10))));
        String battle = battleFixture();
        var output = roundOutput().put("inventoryChanges", new JsonArray().add(new JsonObject().put("characterId", character)
            .put("inventoryId", potion.getInventoryId().toString()).put("action", "use")));
        BattleService battles = battleService(output);
        var generated = new CompletableFuture<com.eonmux.chatllm.agents.AgentResult>();
        var arrived = new java.util.concurrent.atomic.AtomicInteger();
        var field = BattleService.class.getDeclaredField("battleMasterAgent"); field.setAccessible(true);
        var agent = (com.eonmux.chatllm.agents.BattleMasterAgent) field.get(battles);
        org.mockito.Mockito.when(agent.run(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            if (arrived.incrementAndGet() == 6) generated.complete(com.eonmux.chatllm.agents.AgentResult.builder()
                .status("success").structuredOutput(output).build());
            return io.smallrye.mutiny.Uni.createFrom().completionStage(generated);
        });
        var gate = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) attempts.add(CompletableFuture.supplyAsync(() -> {
            try { gate.await(); battles.generateRound(battle, conversation, new JsonObject().put("title", "Drink potion")).await().indefinitely(); return true; }
            catch (Exception expected) { return false; }
        }, workers));
        gate.countDown(); int successes = 0;
        for (var attempt : attempts) if (attempt.get(30, TimeUnit.SECONDS)) successes++;
        assertEquals(1, successes, "All callers generated against round one; only one may commit");
        int rounds = battles.getBattle(battle).await().indefinitely().getRoundNumber() - 1;
        assertEquals(rounds, count("SELECT COUNT(*) AS n FROM message WHERE conversationId = ? AND messageType = 'NARRATION'", conversation));
        assertEquals(1, rounds);
        assertEquals(2 - rounds, service.getItem(potion.getInventoryId().toString()).await().indefinitely() == null ? 0
            : service.getItem(potion.getInventoryId().toString()).await().indefinitely().getQuantity());
    }
    @Test void failedBattleEffectRollsBackRoundDamageEarlierLootAndNarration() throws Exception {
        sql("INSERT INTO character_stat (statId, conversationId, characterId, statName, statValue, statMax) VALUES (?, ?, ?, 'HP', 10, 20)", id(), conversation, character);
        String battle = battleFixture();
        var output = roundOutput().put("statChanges", new JsonArray().add(new JsonObject().put("characterId", character).put("statName", "HP").put("delta", -4)))
            .put("inventoryChanges", new JsonArray().add(new JsonObject().put("characterId", character).put("action", "add").put("itemName", "Quest token"))
                .add(new JsonObject().put("characterId", character).put("action", "remove").put("itemName", "Missing item")));
        BattleService battles = battleService(output);
        assertThrows(IllegalArgumentException.class, () -> battles.generateRound(battle, conversation, new JsonObject()).await().indefinitely());
        assertEquals(1, battles.getBattle(battle).await().indefinitely().getRoundNumber());
        assertEquals(10.0, service.pool.preparedQuery("SELECT statValue FROM character_stat WHERE conversationId = ?")
            .execute(Tuple.of(conversation)).await().indefinitely().iterator().next().getDouble("statValue"));
        assertTrue(service.getInventory(conversation, character).await().indefinitely().isEmpty());
        assertEquals(0, count("SELECT COUNT(*) AS n FROM message WHERE conversationId = ?", conversation));
    }
    @Test void votingRestoresAndReplacesVotesValidatesOptionsAndRequiresPartyQuorum() throws Exception {
        String battle = battleFixture(); BattleService battles = battleService(roundOutput());
        assertTrue(battles.canManage(conversation, host).await().indefinitely());
        assertFalse(battles.canManage(conversation, player).await().indefinitely());
        assertFalse(battles.canManage(conversation, outsider).await().indefinitely());
        assertThrows(IllegalArgumentException.class, () -> battles.castVote(battle, player, -1, 1).await().indefinitely());
        assertThrows(IllegalArgumentException.class, () -> battles.castVote(battle, player, 1, 1).await().indefinitely());
        assertThrows(IllegalStateException.class, () -> battles.castVote(battle, player, 0, 2).await().indefinitely());
        battles.castVote(battle, player, 0, 1).await().indefinitely();
        battles.castVote(battle, player, 0, 1).await().indefinitely();
        assertEquals(1, battles.getVotes(battle, 1).await().indefinitely().size());
        assertFalse(battles.canResolve(battles.getBattle(battle).await().indefinitely(), player).await().indefinitely());
        battles.castVote(battle, host, 0, 1).await().indefinitely();
        battles.castVote(battle, other, 0, 1).await().indefinitely();
        assertTrue(battles.canResolve(battles.getBattle(battle).await().indefinitely(), player).await().indefinitely());
        battles.resolveRound(battle, conversation).await().indefinitely();
        assertEquals(2, battles.getBattle(battle).await().indefinitely().getRoundNumber());
        assertThrows(IllegalStateException.class, () -> battles.resolveRound(battle, conversation).await().indefinitely());
    }
    @Test void initialEncounterCannotDuplicateActiveFightOrApplyUnchosenDamage() throws Exception {
        var opening = roundOutput().put("statChanges", new JsonArray().add(new JsonObject().put("characterId", character).put("statName", "HP").put("delta", -5)));
        BattleService invalid = battleService(opening);
        assertThrows(IllegalArgumentException.class, () -> invalid.initiateBattle(conversation, host, "Guard", "", 20.0, "A challenge").await().indefinitely());
        assertNull(invalid.getActiveBattle(conversation).await().indefinitely());
        BattleService battles = battleService(roundOutput());
        battles.initiateBattle(conversation, host, "Guard", "", 20.0, "A challenge").await().indefinitely();
        BattleService ready = battles;
        assertThrows(IllegalStateException.class, () -> ready.initiateBattle(conversation, host, "Second foe", "", 20.0, "").await().indefinitely());
        assertEquals(20.0, new JsonObject(ready.getActiveBattle(conversation).await().indefinitely().getBattleData()).getDouble("enemyHp"));
        assertEquals(1, count("SELECT COUNT(*) AS n FROM battle WHERE conversationId = ?", conversation));
        assertEquals(1, count("SELECT COUNT(*) AS n FROM message WHERE conversationId = ?", conversation));
    }
    @Test void customApproachesStayWithinTheirRoundAndTerminalOutcomePersistsInChat() throws Exception {
        String battle = battleFixture(); BattleService battles = battleService(roundOutput());
        battles.proposeAction(battle, 1, "Distract the guards while Elara opens the gate.").await().indefinitely();
        var options = new JsonObject(battles.getBattle(battle).await().indefinitely().getActionOptions()).getJsonArray("actions");
        assertEquals(2, options.size());
        assertEquals("Distract the guards while Elara opens the gate.", options.getJsonObject(1).getString("description"));
        assertThrows(IllegalStateException.class, () -> battles.proposeAction(battle, 2, "Another plan").await().indefinitely());
        assertThrows(IllegalStateException.class, () -> battles.requirePeace(conversation).await().indefinitely());
        battles.endBattle(battle, "RETREAT").await().indefinitely();
        battles.requirePeace(conversation).await().indefinitely();
        assertEquals("RETREAT", battles.getBattle(battle).await().indefinitely().getStatus());
        assertEquals(1, count("SELECT COUNT(*) AS n FROM message WHERE conversationId = ? AND JSON_UNQUOTE(JSON_EXTRACT(agentMetadata, '$.battleId')) = ?", conversation, battle));
        assertThrows(IllegalStateException.class, () -> battles.endBattle(battle, "VICTORY").await().indefinitely());
        assertThrows(IllegalStateException.class, () -> battles.castVote(battle, player, 0, 1).await().indefinitely());
    }

    @Test void roundSubscriptionsMatchTheBattleSchemaAndChatIsSavedBeforeNotification() throws Exception {
        String battle = battleFixture(); BattleService battles = battleService(roundOutput());
        var roundEvent = new CompletableFuture<JsonObject>(); var storyEvent = new CompletableFuture<JsonObject>();
        var roundRegistered = new CompletableFuture<Void>(); var storyRegistered = new CompletableFuture<Void>();
        var rounds = vertx.getDelegate().eventBus().<JsonObject>consumer("rp.battle.round." + conversation);
        var stories = vertx.getDelegate().eventBus().<JsonObject>consumer("conversation:" + conversation + ":message-stream");
        rounds.handler(message -> roundEvent.complete(message.body())); stories.handler(message -> storyEvent.complete(message.body()));
        rounds.completionHandler(result -> { if (result.succeeded()) roundRegistered.complete(null); else roundRegistered.completeExceptionally(result.cause()); });
        stories.completionHandler(result -> { if (result.succeeded()) storyRegistered.complete(null); else storyRegistered.completeExceptionally(result.cause()); });
        roundRegistered.get(5, TimeUnit.SECONDS); storyRegistered.get(5, TimeUnit.SECONDS);
        try {
            battles.generateRound(battle, conversation, new JsonObject().put("title", "Take cover")).await().indefinitely();
            JsonObject event = roundEvent.get(5, TimeUnit.SECONDS), chat = storyEvent.get(5, TimeUnit.SECONDS);
            assertEquals(conversation, event.getString("conversationId")); assertEquals("ACTIVE", event.getString("status"));
            assertEquals(roundOutput().getString("narration"), event.getString("currentNarration"));
            assertFalse(new JsonObject(event.getString("actionOptions")).getJsonArray("actions").isEmpty());
            assertEquals("NARRATION", chat.getString("messageType")); assertEquals(battle, chat.getString("battleId"));
            assertEquals(1, count("SELECT COUNT(*) AS n FROM message WHERE messageUUID = ?", chat.getString("messageId")));
            assertEquals(1, service.pool.preparedQuery("SELECT messageCount FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(conversation)).await().indefinitely().iterator().next().getInteger("messageCount"));
        } finally { rounds.unregister(); stories.unregister(); }
    }

    @Test void arrivalNarrationIsSavedOnceAndDeliveredAsACompletedNarratorBlock() throws Exception {
        var story = new StoryMessageService();
        story.pool = service.pool; story.vertx = vertx;
        String text = "Amir and the party moved from the Village Green to the Inn via inn lane.";
        var delivered = new CompletableFuture<JsonObject>();
        var consumer = vertx.eventBus().<JsonObject>consumer("conversation:" + conversation + ":message-stream")
            .handler(message -> delivered.complete(message.body()));
        consumer.completionHandlerAndAwait();
        try {
            story.narrate(conversation, "round:final-character", text, null).await().indefinitely();
            JsonObject frame = delivered.get(3, TimeUnit.SECONDS);
            assertEquals("NARRATION", frame.getString("messageType"));
            assertEquals("SYSTEM", frame.getString("role"));
            assertTrue(frame.getBoolean("isComplete"));
            assertEquals(text, frame.getString("content"));
            story.narrate(conversation, "round:final-character", text, null).await().indefinitely();
            var messages = service.pool.preparedQuery("SELECT content, messageType FROM message WHERE conversationId = ?")
                .execute(Tuple.of(conversation)).await().indefinitely();
            assertEquals(1, messages.size());
            assertEquals(text, messages.iterator().next().getString("content"));
            assertEquals("NARRATION", messages.iterator().next().getString("messageType"));
            var count = service.pool.preparedQuery("SELECT messageCount FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(conversation)).await().indefinitely().iterator().next().getInteger("messageCount");
            assertEquals(1, count);
        } finally { consumer.unregisterAndAwait(); }
    }

    @Test void arrivalResidentsAreStableStayLocalAndGreetAfterTheNarratorOnlyOnce() throws Exception {
        var story = new StoryMessageService(); story.pool=service.pool; story.vertx=vertx;
        var residents = new LocationResidentService(); residents.pool=service.pool; residents.vertx=vertx; residents.storyMessages=story;
        residents.mapService=org.mockito.Mockito.mock(com.eonmux.chatllm.map.MapService.class);
        org.mockito.Mockito.when(residents.mapService.updateCharacterPosition(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.anyFloat()))
            .thenReturn(io.smallrye.mutiny.Uni.createFrom().voidItem());
        var area=com.eonmux.chatllm.models.LocalArea.builder().localAreaId(UUID.randomUUID()).name("Inn").areaType("inn").description("A busy common room").build();
        var node=com.eonmux.chatllm.models.MapLocation.builder().locationId(UUID.randomUUID()).name("Village").x(1f).y(2f).z(0f).build();
        var stage=com.eonmux.chatllm.map.StageContext.builder().mapId(id()).node(node).area(area).build();
        sql("INSERT INTO world_state (stateId,conversationId,currentAreaId) VALUES (?,?,?)",id(),conversation,area.getLocalAreaId().toString());
        sql("UPDATE conversation SET groupState='WAITING_FOR_USER', currentSpeakerId=NULL WHERE conversationGUID=?",conversation);
        sql("UPDATE conversation_character SET isActive=TRUE,isIntroduced=TRUE WHERE conversationId=?",conversation);
        var context=com.eonmux.chatllm.agents.AgentContext.builder().conversationId(conversation).stage(stage).build();
        var resident=residents.prepare(context).await().indefinitely();
        assertEquals(resident.getString("id"),residents.prepare(context).await().indefinitely().getString("id"));
        assertEquals(1,count("SELECT COUNT(*) AS n FROM `character` WHERE JSON_EXTRACT(metadata,'$.residentOfAreaId')=?",area.getLocalAreaId().toString()));
        assertTrue(residents.onstage(conversation).await().indefinitely().contains(resident.getString("id")));
        assertEquals(List.of(character),residents.partyCharacters(conversation,List.of(character,resident.getString("id"))).await().indefinitely());
        story.narrate(conversation,"arrival:test","The party enters the inn.",null).await().indefinitely();
        var delivered=new CompletableFuture<JsonObject>();
        var consumer=vertx.eventBus().<JsonObject>consumer("conversation:"+conversation+":message-stream").handler(message -> delivered.complete(message.body()));
        consumer.completionHandlerAndAwait();
        try {
            residents.greetArrival(conversation,"arrival:test",resident,"The party enters the inn.").await().indefinitely();
            var frame=delivered.get(3,TimeUnit.SECONDS);
            assertEquals(resident.getString("id"),frame.getJsonObject("character").getString("id"));
            assertFalse(frame.getJsonObject("character").containsKey("systemPrompt"));
            residents.greetArrival(conversation,"arrival:test",resident,"The party enters the inn.").await().indefinitely();
            var messages=service.pool.preparedQuery("SELECT messageType,characterId FROM message WHERE conversationId=? ORDER BY seq").execute(Tuple.of(conversation)).await().indefinitely();
            var ordered=new ArrayList<io.vertx.mutiny.sqlclient.Row>(); messages.forEach(ordered::add);
            assertEquals(2,ordered.size()); assertEquals("NARRATION",ordered.get(0).getString("messageType"));
            assertEquals("DIALOGUE",ordered.get(1).getString("messageType")); assertEquals(resident.getString("id"),ordered.get(1).getString("characterId"));
            assertEquals(2,service.pool.preparedQuery("SELECT messageCount FROM conversation WHERE conversationGUID=?").execute(Tuple.of(conversation)).await().indefinitely().iterator().next().getInteger("messageCount"));
            String trigger=id(), boundary=id();
            sql("INSERT INTO message (messageUUID,conversationId,role,content) VALUES (?,?,'user','Enter the inn')",trigger,conversation);
            sql("INSERT INTO message (messageUUID,conversationId,role,content) VALUES (?,?,'assistant','After you.')",boundary,conversation);
            sql("UPDATE conversation SET groupState='CHARACTER_RESPONDING' WHERE conversationGUID=?",conversation);
            story.residentGreeting(conversation,trigger,boundary,resident,"Welcome back.").await().indefinitely();
            assertEquals(5,count("SELECT COUNT(*) AS n FROM message WHERE conversationId=?",conversation));
            String nextBoundary=id();
            sql("INSERT INTO message (messageUUID,conversationId,role,content) VALUES (?,?,'assistant','Another response')",nextBoundary,conversation);
            sql("INSERT INTO message (messageUUID,conversationId,role,content) VALUES (?,?,'user','Wait outside instead')",id(),conversation);
            story.residentGreeting(conversation,trigger,nextBoundary,resident,"This greeting is stale.").await().indefinitely();
            assertEquals(7,count("SELECT COUNT(*) AS n FROM message WHERE conversationId=?",conversation));
            sql("UPDATE world_state SET currentAreaId=? WHERE conversationId=?",id(),conversation);
            assertFalse(residents.canWelcome(conversation,resident.getString("areaId")).await().indefinitely());
            residents.greetArrival(conversation,"later",resident,"The party has left.").await().indefinitely();
            assertEquals(7,count("SELECT COUNT(*) AS n FROM message WHERE conversationId=?",conversation));
            residents.leave(conversation).await().indefinitely();
            assertFalse(residents.onstage(conversation).await().indefinitely().contains(resident.getString("id")));
            assertTrue(residents.onstage(conversation).await().indefinitely().contains(character));
        } finally { consumer.unregisterAndAwait(); }
    }

}
