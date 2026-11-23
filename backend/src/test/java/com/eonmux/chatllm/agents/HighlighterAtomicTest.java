package com.eonmux.chatllm.agents;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.mutiny.sqlclient.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.eonmux.chatllm.testing.SqlTestSupport.*;

class HighlighterAtomicTest {
    static AgentContext context() {
        return AgentContext.builder().conversationId("chat").userId("player").characterIdsInScene(List.of("elara"))
            .recentMessages(new JsonArray().add(new JsonObject().put("role","user").put("content","I inspect the door."))).build();
    }
    static JsonObject entry() { return new JsonObject().put("summary","The player inspected the door.").put("visibility","ALL").put("importance",0.5).put("tags",new JsonArray().add("exploration")); }
    static class Scripted extends HighlighterAgent {
        int calls; boolean repeat;
        Scripted(Pool pool,boolean repeat) { super(null,pool,null,null); this.repeat=repeat; }
        @Override protected Uni<JsonObject> callLlmJson(String system,String prompt,float temp,int max,String model) {
            JsonObject wire=new JsonObject().put("entry",calls++==0 || repeat ? entry() : null);
            return Uni.createFrom().item(new JsonObject().put("choices",new JsonArray().add(new JsonObject().put("message",new JsonObject().put("content",wire.encode())))));
        }
    }
    @SuppressWarnings("unchecked")
    @Test void collectsOneEntryPerCallAndAwaitsItsCacheBeforeReturning() throws Exception {
        Pool db=mock(Pool.class); PreparedQuery<RowSet<Row>> query=mock(PreparedQuery.class);
        var written=new CompletableFuture<RowSet<Row>>();
        when(db.preparedQuery(anyString())).thenReturn(query);
        when(query.execute(any(Tuple.class))).thenReturn(Uni.createFrom().completionStage(written));
        var agent=new Scripted(db,false);
        var result=agent.execute(context(),"test").subscribeAsCompletionStage();
        assertEquals(2,agent.calls); assertFalse(result.toCompletableFuture().isDone());
        written.complete(affected(1));
        assertEquals(1,result.toCompletableFuture().get(2,TimeUnit.SECONDS).getStructuredOutput().getJsonArray("entries").size());
    }
    @Test void duplicateEntriesFailBeforeWritingAnyCache() {
        Pool db=mock(Pool.class); var agent=new Scripted(db,true);
        assertThrows(IllegalStateException.class,()->agent.execute(context(),"test").await().indefinitely());
        verifyNoInteractions(db);
    }
    @Test void oneMemoryRecordCannotCarryMultipleEntriesOrUnknownVisibilityIds() {
        assertFalse(HighlighterAgent.validEntry(new JsonObject().put("entry",new JsonArray().add(entry())),context()));
        assertFalse(HighlighterAgent.validEntry(new JsonObject().put("entry",entry().put("visibility",new JsonArray().add("outsider"))),context()));
        assertTrue(HighlighterAgent.validEntry(new JsonObject().put("entry",entry().put("visibility",new JsonArray().add("elara"))),context()));
        assertFalse(HighlighterAgent.validEntry(new JsonObject().put("entry",entry().put("importance",2)),context()));
    }
    @Test void staleRoundsCannotWriteTheMemoryCache() {
        var db=pool((sql,parameters)-> { assertTrue(sql.contains("SELECT c.conversationGUID")); return rows(); });
        var context=context(); context.setPostDialogue(true); context.setTriggerMessageId("player"); context.setRoundLastMessageId("reply");
        var agent=new Scripted(db,false);
        assertThrows(IllegalStateException.class,()->agent.execute(context,"test").await().indefinitely());
    }

}
