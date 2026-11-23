package com.eonmux.chatllm.agents;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BattleAtomicTest {
    static JsonObject resolution(String outcome,double hp) {
        return new JsonObject().put("outcome",outcome).put("battleState",new JsonObject().put("enemyHp",hp).put("partyStatus","Ready").put("roundOutcome","The foe staggered."))
            .put("statChanges",new JsonArray()).put("inventoryChanges",new JsonArray());
    }
    static class Scripted extends BattleMasterAgent {
        List<String> calls=new ArrayList<>(); String outcome; boolean inject;
        Scripted(String outcome,boolean inject) { super(null,null,null,null); this.outcome=outcome; this.inject=inject; }
        @Override protected Uni<JsonObject> callLlmJson(String system,String prompt,float temp,int max,String model) {
            JsonObject wire;
            if (system.equals(BATTLE_MASTER_SYSTEM)) { calls.add("judge"); wire=resolution(outcome,outcome.equals("VICTORY")?0:10); }
            else {
                calls.add("options"); wire=new JsonObject().put("actions",new JsonArray()
                    .add(new JsonObject().put("title","Strike").put("description","Attempt a strike").put("type","attack"))
                    .add(new JsonObject().put("title","Withdraw").put("description","Attempt retreat").put("type","retreat")));
                if (inject) wire.put("battleState",new JsonObject().put("enemyHp",0));
            }
            return Uni.createFrom().item(new JsonObject().put("choices",new JsonArray().add(new JsonObject().put("message",new JsonObject().put("content",wire.encode())))));
        }
        @Override protected Uni<JsonObject> callLlmProse(String system,String prompt,float temp,int max,String model) {
            calls.add("narrate");
            assertTrue(prompt.contains("AUTHORITATIVE ADJUDICATION"));
            return Uni.createFrom().item(new JsonObject().put("choices",new JsonArray().add(new JsonObject().put("message",new JsonObject().put("content","The foe staggered.")))));
        }
        AgentResult generate() { return execute(AgentContext.builder().triggerContent("chosen attempt").build(),"test").await().indefinitely(); }
    }
    @Test void judgesOnceThenWritesNarrationThenOffersChoices() {
        var agent=new Scripted("ONGOING",false); var output=agent.generate().getStructuredOutput();
        assertEquals(List.of("judge","narrate","options"),agent.calls);
        assertEquals(10,output.getJsonObject("battleState").getDouble("enemyHp"));
        assertFalse(output.getBoolean("battleEnded")); assertNull(output.getValue("endStatus"));
    }
    @Test void terminalOutcomeSkipsTheOptionsCallAndComesOnlyFromTheJudgment() {
        var agent=new Scripted("VICTORY",false); var output=agent.generate().getStructuredOutput();
        assertEquals(List.of("judge","narrate"),agent.calls);
        assertTrue(output.getBoolean("battleEnded")); assertEquals("VICTORY",output.getString("endStatus"));
        assertTrue(output.getJsonArray("actions").isEmpty());
    }
    @Test void presentationCannotSmuggleInASecondMechanicalResult() {
        var agent=new Scripted("ONGOING",true); assertThrows(IllegalArgumentException.class,agent::generate);
        assertEquals(List.of("judge","narrate","options","options"),agent.calls);
    }
    @Test void contradictoryOutcomesAndOpeningEffectsAreRejected() {
        assertFalse(BattleMasterAgent.validResolution(resolution("ONGOING",0),AgentContext.builder().build()));
        assertFalse(BattleMasterAgent.validResolution(resolution("VICTORY",10),AgentContext.builder().build()));
        var opening=AgentContext.builder().battleContext(new JsonObject().put("roundNumber",0)).characterSheet(new JsonObject().put("enemyHp",20)).build();
        assertFalse(BattleMasterAgent.validResolution(resolution("ONGOING",10),opening));
        assertTrue(BattleMasterAgent.validResolution(resolution("ONGOING",20),opening));
        assertFalse(BattleMasterAgent.validResolution(resolution("ONGOING",20).put("narration","mixed"),opening));
    }
}
