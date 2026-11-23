package com.eonmux.chatllm.agents;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JudgmentProtocolTest {
    @ParameterizedTest @ValueSource(strings = {"", "null", "[]", "true", "{\"approved\":true", "{\"approved\":true} {\"approved\":false}",
        "{\"approved\":true,\"approved\":false}", "```json\n{\"approved\":true}\n```", "Yes, {\"approved\":true}"})
    void refusesPartialMultipleAndAmbiguousObjects(String wire) { assertThrows(IllegalArgumentException.class, () -> JudgmentProtocol.object(wire)); }
    @ParameterizedTest @ValueSource(strings = {"{\"approved\":null}", "{\"approved\":\"true\"}", "{\"approved\":false}",
        "{\"approved\":true,\"move\":true}", "{\"approved\":false,\"reason\":\"\"}"})
    void invalidReviewNeverBecomesApproval(String wire) { assertThrows(IllegalArgumentException.class, () -> new SkepticAgent(null,null,null,null).parseVerdict(wire)); }
    @Test void oneVerdictCanHaveSupportingMetadata() {
        assertTrue(new SkepticAgent(null,null,null,null).parseVerdict("{\"approved\":true,\"reason\":\"Consistent\"}").isApproved());
        assertFalse(new SkepticAgent(null,null,null,null).parseVerdict("{\"approved\":false,\"reason\":\"Wrong scene\",\"severity\":\"moderate\"}").isApproved());
    }
    static class Request extends BaseAgent {
        List<String> replies; int calls;
        Request(String... replies) { super("test",null,null,null,null); this.replies=List.of(replies); }
        @Override protected Uni<JsonObject> callLlmJson(String system,String prompt,float temp,int max,String model) {
            String text=replies.get(Math.min(calls++,replies.size()-1));
            return Uni.createFrom().item(new JsonObject().put("choices",new JsonArray().add(new JsonObject().put("message",new JsonObject().put("content",text)))));
        }
        Uni<JsonObject> request() { return callJudgment("review","context",300,"test",JudgmentProtocol::review); }
        @Override protected Uni<AgentResult> execute(AgentContext context,String model) { return Uni.createFrom().nullItem(); }
    }
    @Test void retriesExactlyOnceAndExposesFailureRatherThanANoOp() {
        var request=new Request("{\"approved\":true", "{\"approved\":true}");
        assertNotNull(request.request().await().indefinitely()); assertEquals(2,request.calls);
        var invalid=new Request("{}");
        assertThrows(IllegalArgumentException.class,()->invalid.request().await().indefinitely()); assertEquals(2,invalid.calls);
    }
}
