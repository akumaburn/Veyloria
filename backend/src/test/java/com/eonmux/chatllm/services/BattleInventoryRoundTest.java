package com.eonmux.chatllm.services;

import com.eonmux.chatllm.agents.*;
import com.eonmux.chatllm.models.Battle;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static com.eonmux.chatllm.testing.SqlTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BattleInventoryRoundTest {
    BattleService service;
    Vertx vertx;
    BattleMasterAgent agent;
    AtomicBoolean claimed = new AtomicBoolean();
    AtomicReference<JsonObject> saved = new AtomicReference<>();
    AtomicReference<String> status = new AtomicReference<>();
    Battle battle;
    @BeforeEach void setup() throws Exception {
        vertx = Vertx.vertx();
        service = spy(new BattleService()); service.vertx = vertx;
        battle = Battle.builder().battleId(UUID.randomUUID()).conversationId(UUID.randomUUID()).roundNumber(2)
            .battleData(new JsonObject().put("inventoryChanges", new JsonArray().add(new JsonObject().put("itemName", "old loot")))
                .put("statChanges", new JsonArray().add(new JsonObject().put("delta", -99))).encode()).build();
        doReturn(Uni.createFrom().item(battle)).when(service).getBattle(anyString());
        doAnswer(call -> Uni.createFrom().item((AgentContext) call.getArgument(0))).when(service).populatePartyState(any());
        service.pool = pool((sql, parameters) -> {
            if (!sql.contains("UPDATE battle SET")) return rows(row(java.util.Map.of("statId", "hp")));
            assertTrue(sql.contains("roundNumber = ? AND status = 'ACTIVE'"), "Persisted rounds must use a compare-and-set condition");
            assertEquals(2, parameters.getInteger(6));
            saved.set(new JsonObject(parameters.getString(3))); status.set(parameters.getString(4));
            return affected(claimed.compareAndSet(false, true) ? 1 : 0);
        });
        var connection = mock(io.vertx.mutiny.sqlclient.SqlConnection.class);
        when(connection.preparedQuery(anyString())).thenAnswer(call -> service.pool.preparedQuery(call.getArgument(0)));
        doAnswer(call -> ((java.util.function.Function<io.vertx.mutiny.sqlclient.SqlConnection, Uni<?>>) call.getArgument(0)).apply(connection))
            .when(service.pool).withTransaction(any());
        service.inventoryService = mock(InventoryService.class);
        when(service.inventoryService.applyBattleChanges(any(), anyString(), any())).thenReturn(Uni.createFrom().voidItem());
        service.storyMessageService = mock(StoryMessageService.class);
        when(service.storyMessageService.persist(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(Uni.createFrom().item("message"));
        agent = mock(BattleMasterAgent.class);
        var field = BattleService.class.getDeclaredField("battleMasterAgent"); field.setAccessible(true); field.set(service, agent);
    }
    @AfterEach void close() { vertx.closeAndAwait(); }
    void output(boolean ended) {
        var result = new JsonObject().put("narration", "The foe falls.").put("actions", new JsonArray().add(new JsonObject().put("title", "Attack").put("description", "Press forward"))).put("battleState", new JsonObject())
            .put("battleEnded", ended).put("endStatus", ended ? "VICTORY" : null)
            .put("inventoryChanges", new JsonArray().add(new JsonObject().put("itemName", "new loot")))
            .put("statChanges", new JsonArray().add(new JsonObject().put("characterId", UUID.randomUUID().toString()).put("statName", "HP").put("delta", -1)));
        when(agent.run(any())).thenReturn(Uni.createFrom().item(AgentResult.builder().status("success").structuredOutput(result).build()));
    }
    @Test void storesOnlyCurrentRoundEffectsAndPreventsDuplicateRoundCommits() {
        output(false);
        service.generateRound(battle.getBattleId().toString(), battle.getConversationId().toString(), null).await().indefinitely();
        assertEquals(1, saved.get().getJsonArray("inventoryChanges").size());
        assertEquals("new loot", saved.get().getJsonArray("inventoryChanges").getJsonObject(0).getString("itemName"));
        assertEquals(1, saved.get().getJsonArray("statChanges").size());
        assertThrows(IllegalStateException.class, () -> service.generateRound(battle.getBattleId().toString(), battle.getConversationId().toString(), null).await().indefinitely());
    }
    @Test void tiesChooseTheFirstListedAction() {
        var first = com.eonmux.chatllm.models.BattleVote.builder().optionIndex(2).build();
        var second = com.eonmux.chatllm.models.BattleVote.builder().optionIndex(0).build();
        assertEquals(0, service.determineWinningOption(java.util.List.of(first, second)));
        assertEquals(0, service.determineWinningOption(java.util.List.of(second, first)));
    }
    @Test void winningRoundPersistsVictorySoFinalLootCanBeApplied() {
        output(true);
        service.generateRound(battle.getBattleId().toString(), battle.getConversationId().toString(), null).await().indefinitely();
        assertEquals("VICTORY", status.get());
        assertEquals("VICTORY", saved.get().getString("endStatus"));
    }
}
