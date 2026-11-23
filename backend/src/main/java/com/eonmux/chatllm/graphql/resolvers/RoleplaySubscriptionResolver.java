package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.map.models.JourneyStepResult;
import com.eonmux.chatllm.models.CharacterInventory;
import com.eonmux.chatllm.models.CharacterProposal;
import com.eonmux.chatllm.models.CharacterRelationship;
import com.eonmux.chatllm.models.CharacterStat;
import com.eonmux.chatllm.models.DiceRoll;
import com.eonmux.chatllm.models.GeneratedImage;
import com.eonmux.chatllm.models.ImageGenerationProgress;
import com.eonmux.chatllm.models.MapCharacterPosition;
import com.eonmux.chatllm.models.MapItemPosition;
import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.models.Scene;
import com.eonmux.chatllm.models.Achievement;
import com.eonmux.chatllm.models.UserAchievement;
import com.eonmux.chatllm.models.WorldState;
import com.eonmux.chatllm.services.auth.AuthContext;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.graphql.api.Subscription;
import io.smallrye.mutiny.Multi;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.GraphQLApi;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.graphql.Name;

/**
 * GraphQL subscription resolver for roleplay features.
 * Bridges Vert.x event bus to SmallRye GraphQL Multi streams.
 * All methods return proper model objects for SmallRye schema generation.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class RoleplaySubscriptionResolver {

    @Inject
    Vertx vertx;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    ServiceHelper serviceHelper;

    @Inject
    com.eonmux.chatllm.services.ConversationSubscriptionGuard subscriptionGuard;

    @Subscription
    public Multi<WorldState> worldStateChanged(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(
                        RoleplayEventAddresses.WORLD_STATE_CHANGED + conversationId, WorldState.class));
    }

    @Subscription
    public Multi<DiceRoll> diceRolled(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(
                        RoleplayEventAddresses.DICE_ROLLED + conversationId, DiceRoll.class));
    }

    @Subscription
    public Multi<Scene> sceneChanged(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(
                        RoleplayEventAddresses.SCENE_CHANGED + conversationId, Scene.class));
    }

    @Subscription
    public Multi<CharacterStat> statChanged(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(
                        RoleplayEventAddresses.STAT_CHANGED + conversationId, CharacterStat.class));
    }

    @Subscription
    public Multi<CharacterRelationship> relationshipChanged(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(
                        RoleplayEventAddresses.RELATIONSHIP_CHANGED + conversationId, CharacterRelationship.class));
    }

    @Subscription
    public Multi<CharacterInventory> inventoryChanged(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(
                        RoleplayEventAddresses.INVENTORY_CHANGED + conversationId, CharacterInventory.class));
    }

    @Subscription
    public Multi<ImageGenerationProgress> imageGenerationProgress(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(
                        RoleplayEventAddresses.IMAGE_PROGRESS + conversationId, ImageGenerationProgress.class));
    }

    @Subscription
    public Multi<GeneratedImage> imageGenerated(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(
                        RoleplayEventAddresses.IMAGE_GENERATED + conversationId, GeneratedImage.class));
    }

    @Subscription
    public Multi<UserAchievement> achievementUnlocked(@Name("userId") String userId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        if (!auth.getUserId().toString().equals(userId)) {
            throw new RuntimeException("Can only subscribe to your own achievements");
        }
        String address = RoleplayEventAddresses.ACHIEVEMENT_UNLOCKED + userId;
        return Multi.createFrom().<UserAchievement>emitter(emitter -> {
            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(address);
            consumer.handler(message -> {
                JsonObject data = message.body();
                Achievement ach = Achievement.builder()
                    .achievementId(data.getString("achievementId"))
                    .name(data.getString("name"))
                    .description(data.getString("description"))
                    .icon(data.getString("icon"))
                    .category(data.getString("category"))
                    .tier(data.getString("tier"))
                    .xpReward(data.getInteger("xpReward"))
                    .isHidden(data.getBoolean("isHidden", false))
                    .build();
                UserAchievement ua = UserAchievement.builder()
                    .userId(UUID.fromString(userId))
                    .achievementId(data.getString("achievementId"))
                    .progress(1.0)
                    .isComplete(true)
                    .completedAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                    .achievement(ach)
                    .build();
                emitter.emit(ua);
            });
            emitter.onTermination(() -> consumer.unregister());
        });
    }

    @Subscription
    public Multi<CharacterProposal> characterProposalCreated(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(
                        RoleplayEventAddresses.CASTING_PROPOSAL + conversationId, CharacterProposal.class));
    }

    @Subscription
    public Multi<MapCharacterPosition> mapEntityMoved(@Name("mapId") String mapId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        // mapEntityMoved is keyed on mapId, so gate on map access.
        return serviceHelper.verifyMapAccess(mapId, auth.getUserId().toString())
                .onItem().transformToMulti(hasAccess -> {
                    if (!hasAccess) {
                        log.warn("Subscription access denied: user {} -> map {}", auth.getUserId(), mapId);
                        return Multi.createFrom().empty();
                    }
                    return createTypedEventBusMulti(RoleplayEventAddresses.MAP_ENTITY_MOVED + mapId, MapCharacterPosition.class);
                });
    }

    @Subscription
    public Multi<MapItemPosition> mapItemDiscovered(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(RoleplayEventAddresses.MAP_ITEM_DISCOVERED + conversationId, MapItemPosition.class));
    }

    @Subscription
    public Multi<MapLocation> locationDiscovered(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(RoleplayEventAddresses.MAP_LOCATION_DISCOVERED + conversationId, MapLocation.class));
    }

    @Subscription
    public Multi<JourneyStepResult> travelJourneyStep(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedConversationStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti(RoleplayEventAddresses.TRAVEL_JOURNEY_STEP + conversationId, JourneyStepResult.class));
    }

    /**
     * Gate a conversation-scoped stream on conversation access (owner or active
     * participant). Returns an empty Multi (silent denial) if access is denied.
     */
    private <T> Multi<T> gatedConversationStream(String conversationId, String userId,
                                                  Supplier<Multi<T>> streamSupplier) {
        return subscriptionGuard.multi(conversationId, userId, streamSupplier);
    }

    /**
     * Create a typed Mutiny Multi that bridges the Vert.x event bus and deserializes
     * events into the specified model class via io.vertx.core.json.JsonObject.mapTo().
     */
    private <T> Multi<T> createTypedEventBusMulti(String address, Class<T> type) {
        return Multi.createFrom().<T>emitter(emitter -> {
            log.debug("Creating typed event bus subscription for address: {} -> {}", address, type.getSimpleName());

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(address);

            // Defensive guard: if anything between consumer registration and
            // emitter.onTermination throws, the consumer would leak. Catch and
            // unregister explicitly before failing the emitter.
            try {
                consumer.handler(message -> {
                    JsonObject data = message.body();
                    log.trace("Received event on address {}: {}", address, data.encode());

                    try {
                        T mapped = data.mapTo(type);
                        emitter.emit(mapped);
                    } catch (Exception e) {
                        log.warn("Failed to map event to {} on address '{}': {}. Payload keys: {}",
                            type.getSimpleName(), address, e.getMessage(), data.fieldNames());
                    }
                });

                consumer.completionHandler(ar -> {
                    if (ar.succeeded()) {
                        log.info("EventBus consumer registered for address: {}", address);
                    } else {
                        log.error("Failed to register EventBus consumer for address: {}", address, ar.cause());
                        consumer.unregister();
                        emitter.fail(ar.cause());
                    }
                });

                emitter.onTermination(() -> {
                    log.debug("Unregistering event bus consumer for address: {}", address);
                    consumer.unregister();
                });
            } catch (RuntimeException setupErr) {
                log.error("EventBus subscription setup failed for {}; unregistering consumer", address, setupErr);
                consumer.unregister();
                emitter.fail(setupErr);
            }
        });
    }

}
