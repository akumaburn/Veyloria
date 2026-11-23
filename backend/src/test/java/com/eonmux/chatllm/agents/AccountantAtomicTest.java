package com.eonmux.chatllm.agents;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountantAtomicTest {
    static class Scripted extends AccountantAgent {
        List<String> requests=new ArrayList<>(); int inventoryCalls; boolean repeat;
        Scripted(boolean repeat) { super(null,null,null,null); this.repeat=repeat; }
        @Override protected Uni<JsonObject> callLlmJson(String system,String prompt,float temp,int tokens,String model) {
            String domain=Arrays.stream(BookkeepingDecision.values()).filter(s->system.contains("Judge ONLY "+s.name()+".")).findFirst().orElseThrow().name();
            requests.add(domain);
            JsonObject wire=new JsonObject().putNull("change");
            if (domain.equals("INVENTORY") && (inventoryCalls++==0 || repeat)) {
                wire.put("change",new JsonObject().put("userId","player").put("action","add").put("itemName","Lantern").put("quantity",repeat ? inventoryCalls : 1));
            }
            return Uni.createFrom().item(new JsonObject().put("choices",new JsonArray().add(new JsonObject().put("message",new JsonObject().put("content",wire.encode())))));
        }
        AgentResult runCollection() { return collect(AgentContext.builder().sceneResolutionComplete(true).build(),"round","test").await().indefinitely(); }
    }
    @Test void eachRequestReturnsOneOperationThenAnExplicitTerminatorBeforeTheNextDomain() {
        var agent=new Scripted(false); var result=agent.runCollection();
        assertEquals(List.of("INVENTORY","INVENTORY","STATS","RELATIONSHIPS","WORLD"),agent.requests);
        assertEquals(1,result.getStructuredOutput().getJsonArray("inventoryChanges").size());
        assertFalse(result.getStructuredOutput().containsKey("worldStateChanges"));
    }
    @Test void aRepeatedIdentityCannotDoubleApplyEvenWithADifferentQuantity() {
        var agent=new Scripted(true);
        assertThrows(IllegalStateException.class,agent::runCollection);
        assertEquals(List.of("INVENTORY","INVENTORY"),agent.requests);
    }
    @Test void domainsRejectOtherDomainsBatchesAndContradictoryOwnership() {
        assertFalse(BookkeepingDecision.STATS.valid(new JsonObject().put("change",new JsonArray()),true));
        assertFalse(BookkeepingDecision.STATS.valid(new JsonObject().put("change",new JsonObject().put("currentArea","Inn")),true));
        assertFalse(BookkeepingDecision.INVENTORY.valid(new JsonObject().put("change",new JsonObject().put("userId","a").put("characterId","b").put("action","use").put("itemName","Potion")),true));
        assertFalse(BookkeepingDecision.WORLD.valid(new JsonObject().put("change",new JsonObject().put("currentArea","Inn")),true));
        assertFalse(BookkeepingDecision.WORLD.valid(new JsonObject().put("change",new JsonObject().put("weatherChange","Clear").put("timeAdvance",true)),true));
        assertFalse(BookkeepingDecision.WORLD.valid(new JsonObject(),true));
        assertTrue(BookkeepingDecision.WORLD.valid(new JsonObject().putNull("change"),true));
    }
    @Test void aDomainCannotCreateBaseAttributesAsStoryStats() {
        assertFalse(BookkeepingDecision.STATS.valid(new JsonObject().put("change",new JsonObject().put("characterId","elara").put("statName","Strength").put("delta",1)),true));
    }
}
