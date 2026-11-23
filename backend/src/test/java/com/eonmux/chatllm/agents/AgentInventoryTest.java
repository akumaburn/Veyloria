package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.models.CharacterInventory;
import com.eonmux.chatllm.services.InventoryService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentInventoryTest {
    InventoryService inventory = mock(InventoryService.class);
    AgentPipeline pipeline = new AgentPipeline(null, null, mock(AppConfig.class), null,
        null, null, null, null, null, null, inventory, null, null, null, null);
    AgentContext context = AgentContext.builder().conversationId("game").userId("player").characterIdsInScene(List.of("elara")).build();

    @Test void waitsForGrantBeforeEquippingTheNewItem() {
        var gate = new CompletableFuture<CharacterInventory>();
        var item = CharacterInventory.builder().inventoryId(UUID.randomUUID()).itemName("Sword").build();
        when(inventory.add(eq("game"), eq("elara"), isNull(), eq("Sword"), isNull(), eq(1), eq("weapon"), isNull(), eq(false), isNull()))
            .thenReturn(Uni.createFrom().completionStage(gate));
        when(inventory.getInventory("game", "elara")).thenReturn(Uni.createFrom().item(List.of(item)));
        when(inventory.equipItem(item.getInventoryId().toString(), true)).thenReturn(Uni.createFrom().item(item));
        var changes = new JsonArray().add(new JsonObject().put("characterId", "elara").put("action", "add").put("itemName", "Sword").put("category", "weapon"))
            .add(new JsonObject().put("characterId", "elara").put("action", "equip").put("itemName", "Sword"));
        var warnings = new ArrayList<String>();
        var done = pipeline.applyAccountantInventory(context, changes, warnings).subscribeAsCompletionStage();
        verify(inventory, never()).getInventory(anyString(), anyString());
        assertFalse(done.toCompletableFuture().isDone());
        gate.complete(item);
        done.toCompletableFuture().join();
        verify(inventory).equipItem(item.getInventoryId().toString(), true);
        assertTrue(warnings.isEmpty());
    }
    @Test void malformedChangesProduceWarningsAndDoNotPreventFollowingValidChanges() {
        when(inventory.removeByName("game", null, "player", "Coin", 3)).thenReturn(Uni.createFrom().item(true));
        var warnings = new ArrayList<String>();
        var changes = new JsonArray().add(new JsonObject().put("characterId", "outsider").put("action", "add").put("itemName", "Sword"))
            .add(new JsonObject().put("characterId", "player").put("action", "remove").put("itemName", "Coin").put("quantity", 3));
        pipeline.applyAccountantInventory(context, changes, warnings).await().indefinitely();
        assertEquals(1, warnings.size());
        verify(inventory).removeByName("game", null, "player", "Coin", 3);
    }
    @Test void promptsClearlySeparateCurrentInventoryFromAlreadyAppliedHistory() {
        var context = AgentContext.builder().characterSheet(new JsonObject().put("elara", new JsonObject().put("inventory", new JsonArray())))
            .inventoryActions(new JsonArray().add(new JsonObject().put("action", "use").put("actionId", "committed-use"))).build();
        var prompt = new StringBuilder();
        InventoryPrompt.appendCommittedActions(prompt, context);
        assertTrue(prompt.toString().contains("CURRENT INVENTORIES (authoritative)"));
        assertTrue(prompt.toString().contains("ALREADY been applied"));
        assertTrue(prompt.toString().contains("committed-use"));
    }
}
