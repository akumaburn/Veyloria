package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.models.Scene;
import com.eonmux.chatllm.services.SceneService;
import com.eonmux.chatllm.testing.SqlTestSupport;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TravelSceneServiceTest {
    @Test void arrivalWaitsForSceneMembershipBeforePublishingTheDestinationScene() {
        TravelSceneService service = new TravelSceneService();
        service.sceneService = mock(SceneService.class); service.mapService = mock(MapService.class);
        service.mysqlPool = SqlTestSupport.pool((sql, parameters) -> SqlTestSupport.affected(1));
        service.vertx = mock(Vertx.class); EventBus bus = mock(EventBus.class); when(service.vertx.eventBus()).thenReturn(bus);
        String conversation = UUID.randomUUID().toString(), character = UUID.randomUUID().toString();
        var destination = MapLocation.builder().locationId(UUID.randomUUID()).name("The gate").build();
        var scene = Scene.builder().sceneId(UUID.randomUUID()).name("The gate").location("The gate").build();
        when(service.sceneService.createScene(eq(conversation), eq("The gate"), eq("The gate"), any())).thenReturn(Uni.createFrom().item(scene));
        when(service.mapService.updateLinkedSceneId(anyString(), anyString())).thenReturn(Uni.createFrom().voidItem());
        var membership = new CompletableFuture<Void>();
        when(service.sceneService.addCharacterToScene(scene.getSceneId().toString(), character)).thenReturn(Uni.createFrom().completionStage(membership));
        var arrival = service.enter(conversation, destination, "traveler", List.of(character)).subscribeAsCompletionStage();
        assertFalse(arrival.toCompletableFuture().isDone()); verifyNoInteractions(bus);
        membership.complete(null); arrival.toCompletableFuture().join();
        verify(bus).publish(eq("rp.scene.changed." + conversation), any());
    }
}
