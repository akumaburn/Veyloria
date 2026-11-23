package com.eonmux.chatllm.services;

import com.eonmux.chatllm.models.ConversationProgress;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Ephemeral, reconnect-readable progress. Old runs cannot overwrite a newer run. */
@ApplicationScoped
public class ConversationProgressService {
    @Inject Vertx vertx;
    private final ConcurrentHashMap<String,ConversationProgress> current = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    public void begin(String conversation, String run) {
        current.compute(conversation,(id,previous) -> {
            if (previous != null && previous.isActive() && previous.getRunId().equals(run)) return previous;
            var next = value(conversation,run,"preparing","Preparing the next scene…",true);
            publish(next); return next;
        });
    }
    public void update(String conversation,String run,String stage,String label) {
        current.computeIfPresent(conversation,(id,previous) -> {
            if (!previous.isActive() || !previous.getRunId().equals(run)) return previous;
            if (previous.getStage().equals(stage) && previous.getLabel().equals(label)) return previous;
            var next = value(conversation,run,stage,label,true); publish(next); return next;
        });
    }
    public void finish(String conversation,String run) {
        current.computeIfPresent(conversation,(id,previous) -> {
            if (!previous.getRunId().equals(run)) return previous;
            var next = value(conversation,run,"complete","",false); publish(next); return null;
        });
    }
    public void cancel(String conversation) {
        var previous=current.get(conversation);
        if(previous!=null) finish(conversation,previous.getRunId());
    }
    public ConversationProgress get(String conversation) { return current.get(conversation); }
    private ConversationProgress value(String conversation,String run,String stage,String label,boolean active) {
        return ConversationProgress.builder().conversationId(conversation).runId(run).sequence(sequence.incrementAndGet())
            .stage(stage).label(label).active(active).updatedAt(Instant.now().toString()).build();
    }
    private void publish(ConversationProgress progress) {
        vertx.eventBus().publish("group:"+progress.getConversationId()+":state-changed",new JsonObject()
            .put("conversationId",progress.getConversationId()).put("processingOnly",true)
            .put("groupState",progress.getRunId().startsWith("opening:") ? "INITIALIZING" : "CHARACTER_RESPONDING")
            .put("currentSpeakerId",(String)null).put("processingStatus",JsonObject.mapFrom(progress)));
    }
}
