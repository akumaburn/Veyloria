package com.eonmux.chatllm.map;

import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.services.SceneService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/** Establishes a destination scene completely before NPC arrival responses run. */
@ApplicationScoped
public class TravelSceneService {
    @Inject SceneService sceneService;
    @Inject MapService mapService;
    @Inject Pool mysqlPool;
    @Inject Vertx vertx;
    @Inject StageService stageService;
    @Inject com.eonmux.chatllm.services.LocationResidentService residents;
    @Inject com.eonmux.chatllm.services.ConversationProgressService progress;

    /** Called after the arrival narration is saved and the route is committed. */
    public Uni<Void> welcome(String conversation,String user,String eventKey,String arrival) {
        String run="arrival:"+eventKey;
        return stageService.getStageContext(conversation).chain(stage -> {
            if (stage == null || !stage.isPresent()) return Uni.createFrom().voidItem();
            return residents.canWelcome(conversation,stage.getArea().getLocalAreaId().toString()).chain(ready -> {
                if (!ready) return Uni.createFrom().voidItem();
                progress.begin(conversation,run);
                progress.update(conversation,run,"residents","Meeting the locals…");
                return residents.prepare(com.eonmux.chatllm.agents.AgentContext.builder()
                    .conversationId(conversation).userId(user).stage(stage).build())
                    .chain(resident -> resident == null ? Uni.createFrom().voidItem() : residents.greetArrival(conversation,eventKey,resident,arrival))
                    .onFailure().recoverWithItem(error -> null)
                    .eventually(() -> progress.finish(conversation,run));
            });
        });
    }

    public Uni<Void> enter(String conversationId, MapLocation destination, String userId, List<String> characters) {
        var scene = destination.getLinkedSceneId() == null
            ? sceneService.createScene(conversationId, destination.getName(), destination.getName(), destination.getDescription())
                .call(created -> mapService.updateLinkedSceneId(destination.getLocationId().toString(), created.getSceneId().toString()))
            : sceneService.activateScene(conversationId, destination.getLinkedSceneId().toString());
        return scene.chain(current -> {
            Uni<Void> sequence = mysqlPool.preparedQuery("UPDATE conversation SET activeSceneId = ? WHERE conversationGUID = ?")
                .execute(Tuple.of(current.getSceneId().toString(), conversationId)).replaceWithVoid();
            for (String character : characters) sequence = sequence.chain(() -> sceneService.addCharacterToScene(current.getSceneId().toString(), character));
            return sequence.invoke(() -> vertx.eventBus().publish(RoleplayEventAddresses.SCENE_CHANGED + conversationId,
                new JsonObject().put("sceneId", current.getSceneId().toString()).put("name", current.getName()).put("location", current.getLocation()).put("isActive", true)));
        });
    }

}
