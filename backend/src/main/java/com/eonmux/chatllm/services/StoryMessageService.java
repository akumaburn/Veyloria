package com.eonmux.chatllm.services;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.eonmux.chatllm.utils.Time4JHelper;

/** Saves story events before notifying the existing chat stream. Stable keys prevent duplicate history. */
@ApplicationScoped
public class StoryMessageService {
    @Inject Pool pool;
    @Inject Vertx vertx;

    public Uni<Void> narrate(String conversationId, String eventKey, String content, String battleId) {
        return pool.withTransaction(db -> db.preparedQuery("SELECT conversationGUID FROM conversation WHERE conversationGUID = ? FOR UPDATE")
            .execute(Tuple.of(conversationId)).chain(() -> persist(db, conversationId, eventKey, content, battleId))).invoke(id -> publish(conversationId, id, content, battleId)).replaceWithVoid();
    }

    public Uni<Void> residentGreeting(String conversation,String trigger,String boundary,JsonObject resident,String words) {
        return writeResidentGreeting(conversation,trigger,boundary,"round:"+boundary,resident,words);
    }
    public Uni<Void> residentGreetingAtArrival(String conversation,String eventKey,JsonObject resident,String words) {
        return writeResidentGreeting(conversation,null,null,eventKey,resident,words);
    }
    private Uni<Void> writeResidentGreeting(String conversation,String trigger,String boundary,String eventKey,JsonObject resident,String words) {
        String event="arrival-greeting:"+eventKey+":"+resident.getString("areaId")+":"+resident.getString("id");
        String id=UUID.nameUUIDFromBytes((conversation+":"+event).getBytes(StandardCharsets.UTF_8)).toString();
        return pool.withTransaction(db -> db.preparedQuery("SELECT conversationGUID FROM conversation WHERE conversationGUID=? FOR UPDATE")
            .execute(Tuple.of(conversation))
            .chain(ignored -> db.preparedQuery("SELECT c.conversationGUID FROM conversation c JOIN world_state w ON w.conversationId=c.conversationGUID WHERE c.conversationGUID=? AND w.currentAreaId=? AND c.currentSpeakerId IS NULL AND c.groupState=? AND (? IS NULL OR (SELECT m.messageUUID FROM message m WHERE m.conversationId=c.conversationGUID AND m.role='assistant' AND m.isDeleted=FALSE ORDER BY m.seq DESC LIMIT 1)=?) AND (? IS NULL OR (SELECT m.messageUUID FROM message m WHERE m.conversationId=c.conversationGUID AND m.role='user' AND m.isDeleted=FALSE ORDER BY m.seq DESC LIMIT 1)=?)")
                .execute(Tuple.from(java.util.Arrays.asList(conversation,resident.getString("areaId"),boundary == null ? "WAITING_FOR_USER" : "CHARACTER_RESPONDING",boundary,boundary,trigger,trigger))))
            .chain(current -> {
                if (current.size()==0) return io.smallrye.mutiny.Uni.createFrom().item(false);
                return db.preparedQuery("INSERT IGNORE INTO message (messageUUID,conversationId,characterId,role,content,displayName,messageType,isBase64Encoded,messageEncoding,agentMetadata,sceneId) SELECT ?,?,?,'assistant',?,?,'DIALOGUE',FALSE,'plain',?,activeSceneId FROM conversation WHERE conversationGUID=?")
                    .execute(Tuple.from(java.util.Arrays.asList(id,conversation,resident.getString("id"),words,resident.getString("name"),new JsonObject().put("storyEvent",event).encode(),conversation)))
                    .chain(insert -> insert.rowCount()==0 ? io.smallrye.mutiny.Uni.createFrom().item(false)
                        : db.preparedQuery("UPDATE conversation SET messageCount=COALESCE(messageCount,0)+1,lastMessageAt=NOW(),lastModified=NOW() WHERE conversationGUID=?")
                            .execute(Tuple.of(conversation)).replaceWith(true));
            })).invoke(saved -> {
                if (!saved) return;
                JsonObject profile=resident.copy(); profile.remove("systemPrompt"); profile.remove("greeting"); profile.remove("areaId");
                vertx.eventBus().publish("conversation:"+conversation+":message-stream",new JsonObject().put("messageId",id).put("conversationId",conversation)
                    .put("role","ASSISTANT").put("characterId",resident.getString("id")).put("character",profile)
                    .put("displayName",resident.getString("name")).put("messageType","DIALOGUE").put("content",words).put("streamingContent",words)
                    .put("isComplete",true).put("createdAt",Time4JHelper.nowUTCString()));
            }).replaceWithVoid();
    }

    Uni<String> persist(io.vertx.mutiny.sqlclient.SqlClient db, String conversationId, String eventKey, String content, String battleId) {
        String id = UUID.nameUUIDFromBytes((conversationId + ":" + eventKey).getBytes(StandardCharsets.UTF_8)).toString();
        JsonObject metadata = new JsonObject().put("storyEvent", eventKey).put("battleId", battleId);
        return db.preparedQuery("""
            INSERT IGNORE INTO message (messageUUID, conversationId, role, content, displayName, messageType,
                isBase64Encoded, messageEncoding, agentMetadata, sceneId)
            SELECT ?, ?, 'system', ?, 'Narrator', 'NARRATION', FALSE, 'plain', ?, activeSceneId
            FROM conversation WHERE conversationGUID = ?
            """).execute(Tuple.of(id, conversationId, content, metadata.encode(), conversationId))
            .chain(rows -> rows.rowCount() == 0 ? Uni.createFrom().item(id)
                : db.preparedQuery("UPDATE conversation SET messageCount = COALESCE(messageCount, 0) + 1, lastMessageAt = NOW(), lastModified = NOW() WHERE conversationGUID = ?")
                    .execute(Tuple.of(conversationId)).replaceWith(id));
    }

    void publish(String conversationId, String id, String content, String battleId) {
        vertx.eventBus().publish("conversation:" + conversationId + ":message-stream",
            new JsonObject().put("messageId", id).put("conversationId", conversationId)
                .put("role", "SYSTEM").put("displayName", "Narrator").put("messageType", "NARRATION")
                .put("content", content).put("streamingContent", content).put("isComplete", true)
                .put("createdAt", Time4JHelper.nowUTCString()).put("battleId", battleId));
    }
}
