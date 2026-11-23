package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.*;
import com.eonmux.chatllm.models.ConversationCharacter;
import com.eonmux.chatllm.models.InterjectionRequest;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.converters.uni.UniReactorConverters;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import io.smallrye.graphql.api.Subscription;
import org.eclipse.microprofile.graphql.Name;


/**
 * GraphQL Subscription resolver for real-time features.
 *
 * Each subscription bridges a Vert.x event-bus channel into a Mutiny / Reactor
 * stream. Subscriptions use a per-subscriber consumer pattern that mirrors
 * {@code RoleplaySubscriptionResolver}: a previous shared-consumer optimization
 * stalled emission until the conversation reload, so it has been removed.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class SubscriptionResolver {

    @Inject
    Vertx vertx;
    @Inject
    Redis redisClient;
    @Inject
    com.eonmux.chatllm.services.UserPresenceService userPresenceService;

    // Active subscriptions tracking
    private final Map<String, MessageConsumer<?>> activeConsumers = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> activeStreams = new ConcurrentHashMap<>();

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    @Inject
    io.vertx.mutiny.sqlclient.Pool mysqlPool;

    @Inject
    com.eonmux.chatllm.services.ConversationSubscriptionGuard subscriptionGuard;

    private <T> Multi<T> gatedConversationStream(String conversationId, String userId,
                                                  java.util.function.Supplier<Multi<T>> streamSupplier) {
        return subscriptionGuard.multi(conversationId, userId, streamSupplier);
    }

    private <T> Publisher<T> gatedConversationPublisher(String conversationId, String userId,
                                                         java.util.function.Supplier<Publisher<T>> streamSupplier) {
        return subscriptionGuard.flux(conversationId, userId, streamSupplier);
    }

    /**
     * Stream messages for a conversation.
     * This is the main subscription for real-time LLM streaming.
     * Returns StreamingMessage for proper GraphQL type generation.
     *
     * PERFORMANCE: Uses shared EventBus consumer per channel to prevent O(n²)
     * message processing when multiple clients subscribe to the same conversation.
     * Instead of each subscription having its own consumer (which processes every message),
     * we have ONE consumer per channel that processes messages ONCE and broadcasts to all emitters.
     */
    @Subscription
    public Multi<StreamingMessage> messageStream(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();
        String channelName = "conversation:" + conversationId + ":message-stream";

        // Per-subscriber consumer pattern (matches RoleplaySubscriptionResolver,
        // which streams roleplay events incrementally without issue). The previous
        // shared-consumer optimization interacted poorly with Mutiny emitters and
        // delivered nothing until the conversation reload — restore the simple
        // pattern first; any cross-subscriber dedup can be reintroduced later
        // without touching the delivery path.
        return gatedConversationStream(conversationId, userId, () ->
        Multi.createFrom().<StreamingMessage>emitter(emitter -> {
            log.debug("Setting up message stream for user {} on conversation {}", userId, conversationId);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);

            try {
                consumer.handler(message -> {
                    JsonObject data = message.body();
                    if (emitter.isCancelled()) return;

                    boolean isComplete = data.getBoolean("isComplete", false);

                    GenerationStats genStats = null;
                    if (isComplete && data.containsKey("generationStats")) {
                        JsonObject stats = data.getJsonObject("generationStats");
                        if (stats != null) {
                            genStats = GenerationStats.builder()
                                .promptTokens(getIntSafe(stats, "promptTokens", 0))
                                .completionTokens(getIntSafe(stats, "completionTokens", 0))
                                .totalTokens(getIntSafe(stats, "totalTokens", 0))
                                .promptEvalMs(getIntSafe(stats, "promptEvalMs", 0))
                                .generationMs(getIntSafe(stats, "generationMs", 0))
                                .totalTimeMs(getIntSafe(stats, "totalTimeMs", 0))
                                .tokensPerSecond(getDoubleSafe(stats, "tokensPerSecond", 0.0))
                                .build();
                        }
                    }

                    StreamingMessage chunk = StreamingMessage.builder()
                        .id(data.getString("messageId"))
                        .content(data.getString("content", ""))
                        .streamingContent(data.getString("streamingContent", ""))
                        .role(data.getString("role", "ASSISTANT").toUpperCase())
                        .isComplete(isComplete)
                        .tokenCount(data.getInteger("tokenCount", 0))
                        .error(data.getString("error"))
                        .createdAt(data.getString("createdAt", java.time.Instant.now().toString()))
                        .characterId(data.containsKey("characterId") ? data.getString("characterId") : null)
                        .character(data.getJsonObject("character") == null ? null : data.getJsonObject("character").mapTo(com.eonmux.chatllm.models.Character.class))
                        .displayName(data.containsKey("displayName") ? data.getString("displayName") : null)
                        .messageType(data.containsKey("messageType") ? data.getString("messageType") : null)
                        .battleId(data.getString("battleId"))
                        .conversationId(conversationId)
                        .generationStats(genStats)
                        .build();

                    try {
                        emitter.emit(chunk);
                    } catch (Exception e) {
                        log.warn("Failed to emit stream chunk for conversation {}: {}", conversationId, e.getMessage());
                    }

                    if (data.getString("error") != null) {
                        log.warn("Stream error for conversation {}: {}", conversationId, data.getString("error"));
                    }
                });

                consumer.completionHandler()
                    .subscribe().with(
                        v -> log.info("EventBus consumer registered for message stream {}", channelName),
                        err -> {
                            log.error("Failed to register message stream consumer for {}", channelName, err);
                            consumer.unregisterAndForget();
                            emitter.fail(err);
                        });

                emitter.onTermination(() -> {
                    log.debug("Tearing down message stream subscription for conversation {}", conversationId);
                    consumer.unregisterAndForget();

                });
            } catch (RuntimeException setupErr) {
                log.error("messageStream setup failed for conversation {}", conversationId, setupErr);
                consumer.unregisterAndForget();
                emitter.fail(setupErr);
            }
        })
        );
    }

    /**
     * Subscribe to conversation updates.
     * Schema: conversationUpdates(conversationId: ID!): Conversation! @auth
     */
    @Subscription
    public Publisher<Conversation> conversationUpdates(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "conversation:" + conversationId + ":updated";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();
                log.debug("Received conversation update event: {}", data.encode());

                // Create conversation object from update
                Conversation conversation = new Conversation();
                conversation.setId(UUID.fromString(data.getString("id")));
                conversation.setOwnerId(UUID.fromString(data.getString("ownerId")));
                conversation.setCharacterId(data.getString("characterId") != null ?
                    UUID.fromString(data.getString("characterId")) : null);
                conversation.setTitle(data.getString("title"));
                conversation.setMessageCount(data.getInteger("messageCount", 0));
                if (data.getString("updatedAt") != null) {
                    conversation.setUpdatedAt(data.getString("updatedAt"));
                }
                if (data.getString("createdAt") != null) {
                    conversation.setCreatedAt(data.getString("createdAt"));
                }

                log.debug("Emitting conversation update to subscription: id={}, title={}",
                    conversation.getId(), conversation.getTitle());
                emitter.next(conversation);
            });

            // Note: subscribeToRedisChannel expects FluxSink<JsonObject>
            // We handle the conversion in the consumer handler above
            // subscribeToRedisChannel(channelName, emitter, subscriptionId);
            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to conversation deletion events.
     */
    @Subscription
    public Publisher<UUID> conversationDeleted() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "user:" + auth.getUserId() + ":conversations:deleted";

        return Flux.create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<String> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                UUID deletedId = UUID.fromString(message.body());
                emitter.next(deletedId);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        });
    }

    /**
     * Subscribe to message edit events.
     */
    @Subscription
    public Publisher<Message> messageEdited(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "conversation:" + conversationId + ":message:edited";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                Message editedMessage = new Message();
                editedMessage.setId(UUID.fromString(data.getString("id")));
                editedMessage.setContent(data.getString("content"));
                editedMessage.setEditCount(data.getInteger("editCount", 0));

                emitter.next(editedMessage);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to message deletion events.
     */
    @Subscription
    public Publisher<UUID> messageDeleted(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "conversation:" + conversationId + ":message:deleted";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<String> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                UUID deletedId = UUID.fromString(message.body());
                emitter.next(deletedId);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to typing indicator events.
     * Schema: typingIndicator(conversationId: ID!): TypingIndicator! @auth
     * Returns TypingIndicator for proper GraphQL type generation.
     */
    @Subscription
    public Publisher<TypingIndicator> typingIndicator(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        // Channel matches what sendTypingIndicator mutation publishes to
        String channelName = "typing.indicator." + conversationId;

        return gatedConversationPublisher(conversationId, userId, () -> Flux.<TypingIndicator>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            log.debug("Setting up typing indicator subscription for conversation {}", conversationId);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                // Don't send user's own typing events back to them
                String typingUserId = data.getString("userId");
                if (auth.getUserId().toString().equals(typingUserId)) {
                    return;
                }

                TypingIndicator indicator = TypingIndicator.builder()
                    .userId(typingUserId)
                    .username(data.getString("username", "User"))
                    .isTyping(data.getBoolean("isTyping", false))
                    .build();

                log.debug("Emitting typing indicator: userId={}, isTyping={}", indicator.getUserId(), indicator.getIsTyping());
                emitter.next(indicator);
            });

            consumer.completionHandler()
                .subscribe().with(
                    v -> log.info("EventBus consumer registered for typing channel: {}", channelName),
                    err -> {
                        log.error("Failed to register EventBus consumer for typing channel: {}", channelName, err);
                        emitter.error(err);
                    }
                );

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to character update events.
     */
    @Subscription
    public Publisher<com.eonmux.chatllm.models.Character> characterUpdated() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "user:" + auth.getUserId() + ":characters:updated";

        return Flux.create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                com.eonmux.chatllm.models.Character character = new com.eonmux.chatllm.models.Character();
                character.setId(UUID.fromString(data.getString("id")));
                character.setName(data.getString("name"));
                character.setDescription(data.getString("description"));
                character.setSystemPrompt(data.getString("systemPrompt"));

                emitter.next(character);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        });
    }

    /**
     * Subscribe to character deletion events.
     */
    @Subscription
    public Publisher<UUID> characterDeleted() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "user:" + auth.getUserId() + ":characters:deleted";

        return Flux.create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<String> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                UUID deletedId = UUID.fromString(message.body());
                emitter.next(deletedId);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        });
    }

    /**
     * Subscribe to system notifications.
     */
    @Subscription
    public Publisher<SystemNotification> systemNotification() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "system:notifications";
        String userChannelName = "user:" + auth.getUserId() + ":notifications";

        return Flux.<SystemNotification>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            // Subscribe to both system-wide and user-specific notifications
            MessageConsumer<JsonObject> systemConsumer = vertx.eventBus().consumer(channelName);
            MessageConsumer<JsonObject> userConsumer = vertx.eventBus().consumer(userChannelName);

            activeConsumers.put(subscriptionId + ":system", systemConsumer);
            activeConsumers.put(subscriptionId + ":user", userConsumer);

            java.util.function.Consumer<io.vertx.mutiny.core.eventbus.Message<JsonObject>> handler = message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();
                SystemNotification notification = SystemNotification.builder()
                    .id(UUID.randomUUID().toString())
                    .type(data.getString("type"))
                    .title(data.getString("title"))
                    .message(data.getString("message"))
                    .severity(data.getString("severity"))
                    .timestamp(java.time.Instant.now().toString())
                    .build();

                emitter.next(notification);
            };

            systemConsumer.handler(handler::accept);
            userConsumer.handler(handler::accept);

            emitter.onDispose(() -> {
                cleanup(subscriptionId + ":system");
                cleanup(subscriptionId + ":user");
                cleanup(subscriptionId);
            });
        });
    }

    /**
     * Subscribe to session expiring events.
     */
    @Subscription
    public Publisher<Integer> sessionExpiring() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        // Create a flux that emits remaining time periodically
        return Flux.interval(Duration.ofSeconds(30))
            .map(tick -> {
                long remainingSeconds = auth.getRemainingTime();

                // Only emit if less than 5 minutes remaining
                if (remainingSeconds < 300) {
                    return (int) remainingSeconds;
                }
                return null;
            })
            .filter(remaining -> remaining != null && remaining > 0)
            .take(10); // Limit to 10 emissions (5 minutes)
    }

    /**
     * Subscribe to session expired events.
     */
    @Subscription
    public Publisher<Boolean> sessionExpired() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "session:" + auth.getTokenId() + ":expired";

        return Flux.create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<Boolean> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                emitter.next(true);
                cleanup(subscriptionId);
                emitter.complete();
            });

            // Also check periodically
            long timerId = vertx.setPeriodic(30000, id -> {
                if (auth.isExpired()) {
                    emitter.next(true);
                    cleanup(subscriptionId);
                    emitter.complete();
                }
            });

            emitter.onDispose(() -> {
                vertx.cancelTimer(timerId);
                cleanup(subscriptionId);
            });
        });
    }

    /**
     * Subscribe to LLM status changes.
     */
    @Subscription
    public Publisher<LLMStatus> llmStatusChanged() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "llm:status:changed";

        return Flux.<LLMStatus>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();
                LLMStatus status = LLMStatus.builder()
                    .isRunning(data.getBoolean("isRunning", false))
                    .modelLoaded(data.getBoolean("modelLoaded", false))
                    .currentModel(data.getString("currentModel"))
                    .availableMemory(data.getLong("availableMemory", 0L))
                    .usedMemory(data.getLong("usedMemory", 0L))
                    .providerType(data.getString("providerType"))
                    .build();

                emitter.next(status);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        });
    }

    /**
     * Subscribe to character switch events for a conversation.
     * Schema: characterSwitch(conversationId: ID!): Character! @auth
     * Returns Character object matching the GraphQL schema.
     */
    @Subscription
    public Publisher<com.eonmux.chatllm.models.Character> characterSwitch(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "conversation:" + conversationId + ":character-switched";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.<com.eonmux.chatllm.models.Character>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                // Create Character object from event data
                com.eonmux.chatllm.models.Character character = new com.eonmux.chatllm.models.Character();
                String newCharacterId = data.getString("newCharacterId");
                if (newCharacterId != null) {
                    character.setId(UUID.fromString(newCharacterId));
                }
                character.setName(data.getString("characterName", ""));
                character.setDescription(data.getString("characterDescription", ""));
                character.setAvatar(data.getString("characterAvatar"));
                character.setSystemPrompt(data.getString("systemPrompt"));
                character.setGreeting(data.getString("greeting"));

                log.debug("Emitting character switch event: id={}, name={}", character.getId(), character.getName());
                emitter.next(character);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to user presence updates.
     * Schema: userPresence: UserPresence! @auth
     * Returns UserPresence object matching the GraphQL schema.
     */
    @Subscription
    public Publisher<UserPresence> userPresence() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "global:user:presence";

        return Flux.<UserPresence>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                UserPresence presence = UserPresence.builder()
                    .userId(data.getString("userId"))
                    .username(data.getString("username", "User"))
                    .status(data.getString("status", "online"))
                    .lastSeen(data.getString("lastSeen", java.time.Instant.now().toString()))
                    .build();

                log.debug("Emitting user presence: userId={}, status={}",
                    presence.getUserId(), presence.getStatus());
                emitter.next(presence);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        });
    }

    /**
     * Subscribe to presence updates for multiple users.
     */
    @Subscription
    public Publisher<UserPresence> presenceUpdates(@Name("userIds") java.util.List<String> userIds) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        if (userIds == null || userIds.isEmpty()) {
            return Flux.empty();
        }

        // Only allow tracking presence of users the caller has a legitimate
        // relation to: themselves, or users they share an active conversation
        // with. Previously any authenticated user could enumerate arbitrary
        // users' online/offline status by passing any userIds.
        String callerId = auth.getUserId().toString();
        java.util.Set<String> requested = new java.util.HashSet<>(userIds);
        requested.remove(callerId);

        return verifyPresenceTargets(requested, callerId)
                .convert().with(UniReactorConverters.toFlux())
                .flatMap(allowed -> {
                    if (!allowed) {
                        log.warn("presenceUpdates denied for user {} tracking {}", callerId, requested);
                        return Flux.empty();
                    }
                    return doPresenceUpdates(requested, callerId);
                });
    }

    /**
     * Verify the caller shares at least one active conversation with each of
     * the requested users.
     */
    private Uni<Boolean> verifyPresenceTargets(java.util.Set<String> targetUserIds, String callerId) {
        if (targetUserIds.isEmpty()) {
            return Uni.createFrom().item(true);
        }
        String query = """
            SELECT COUNT(DISTINCT t.targetUserId) AS count
            FROM (
                SELECT ? AS targetUserId
            ) t
            LEFT JOIN conversation_participant caller_cp
                ON caller_cp.user_id = ? AND caller_cp.is_active = true
            LEFT JOIN conversation_participant target_cp
                ON target_cp.conversation_id = caller_cp.conversation_id
               AND target_cp.user_id = t.targetUserId AND target_cp.is_active = true
            WHERE t.targetUserId IN (%s)
            """.formatted(targetUserIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(",")));
        io.vertx.mutiny.sqlclient.Tuple params = io.vertx.mutiny.sqlclient.Tuple.tuple()
            .addValue(callerId)
            .addValue(callerId);
        for (String targetUserId : targetUserIds) {
            params = params.addValue(targetUserId);
        }
        return mysqlPool.preparedQuery(query)
            .execute(params)
            .map(rows -> {
                long count = rows.iterator().next().getLong("count");
                return count == targetUserIds.size();
            });
    }

    private Publisher<UserPresence> doPresenceUpdates(java.util.Set<String> userIds, String callerId) {
        String subscriptionId = UUID.randomUUID().toString();

        return Flux.<UserPresence>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            // Track which per-user consumer keys we've registered so we can roll
            // back if the loop throws partway through. Without this, an exception
            // at user N leaks consumers 1..N-1 (onDispose below was never reached).
            final java.util.List<String> registeredConsumerKeys = new java.util.ArrayList<>();
            long heartbeatId = -1L;

            try {
                userIds.forEach(userId -> {
                    String channelName = "user:" + userId + ":presence";
                    MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
                    String consumerKey = subscriptionId + ":" + userId;
                    activeConsumers.put(consumerKey, consumer);
                    registeredConsumerKeys.add(consumerKey);

                    consumer.handler(message -> {
                        if (!isActive.get()) {
                            return;
                        }

                        JsonObject data = message.body();
                        UserPresence presence = UserPresence.builder()
                            .userId(userId)
                            .username(data.getString("username", "User"))
                            .status(data.getString("status", "offline"))
                            .lastSeen(data.getString("lastSeen"))
                            .build();

                        emitter.next(presence);
                    });

                    // Get initial presence from Redis. Guard against late
                    // emission after the subscription is cancelled — without
                    // this a slow Redis response would call emitter.next on a
                    // disposed emitter.
                    String presenceKey = "presence:" + userId;
                    redisClient.connect()
                        .compose(connection -> RedisAPI.api(connection).get(presenceKey))
                        .onSuccess(response -> {
                            if (!isActive.get()) return;
                            if (response != null) {
                                try {
                                    JsonObject presenceData = new JsonObject(response.toString());
                                    UserPresence presence = UserPresence.builder()
                                        .userId(presenceData.getString("userId", userId))
                                        .username(presenceData.getString("username", "User"))
                                        .status(presenceData.getString("status", "offline"))
                                        .lastSeen(presenceData.getString("lastSeen"))
                                        .build();
                                    emitter.next(presence);
                                } catch (Exception e) {
                                    emitter.next(UserPresence.builder()
                                        .userId(userId)
                                        .status("offline")
                                        .build());
                                }
                            } else {
                                emitter.next(UserPresence.builder()
                                    .userId(userId)
                                    .status("offline")
                                    .build());
                            }
                        });
                });

                heartbeatId = vertx.setPeriodic(30000, id -> {
                    updateUserPresence(callerId, "online");
                });
            } catch (RuntimeException setupErr) {
                log.warn("presenceUpdates setup failed; rolling back {} consumers", registeredConsumerKeys.size(), setupErr);
                isActive.set(false);
                if (heartbeatId >= 0) vertx.cancelTimer(heartbeatId);
                registeredConsumerKeys.forEach(this::cleanup);
                cleanup(subscriptionId);
                emitter.error(setupErr);
                return;
            }

            final long heartbeatIdFinal = heartbeatId;
            emitter.onDispose(() -> {
                if (heartbeatIdFinal >= 0) vertx.cancelTimer(heartbeatIdFinal);
                registeredConsumerKeys.forEach(this::cleanup);
                cleanup(subscriptionId);
            });
        });
    }

    private void updateUserPresence(String userId, String status) {
        userPresenceService.updatePresence(userId, status);
    }

    // ========== Group Chat Subscriptions ==========

    /**
     * Subscribe to group character response events (streaming).
     *
     * <p><b>Note:</b> Group character streaming currently flows through the
     * main {@link #messageStream} channel
     * ({@code conversation:<id>:message-stream}), not through this dedicated
     * subscription. No publisher writes to
     * {@code group:<id>:character-response} today, so this stream is
     * effectively idle — the subscribe path is kept so a future, group-only
     * streaming channel can be wired up without a schema change.
     *
     * <p>The field mapping mirrors {@link #messageStream} so a future
     * publisher only needs to emit the same JSON shape as the existing
     * streaming payload ({@code messageId}, {@code content}, {@code isComplete},
     * {@code displayName} / {@code characterName}, {@code messageType}, ...).
     */
    @Subscription
    public Publisher<StreamingMessage> groupCharacterResponse(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "group:" + conversationId + ":character-response";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.<StreamingMessage>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                // Accept either "displayName" (matches messageStream) or
                // "characterName" (matches the existing group.character.response.complete
                // publisher naming), so a future streaming publisher can use either.
                String displayName = data.containsKey("displayName")
                    ? data.getString("displayName")
                    : data.getString("characterName");

                StreamingMessage chunk = StreamingMessage.builder()
                    .id(data.getString("messageId"))
                    .content(data.getString("content", ""))
                    .streamingContent(data.getString("streamingContent", ""))
                    .role(data.getString("role", "ASSISTANT").toUpperCase())
                    .isComplete(data.getBoolean("isComplete", false))
                    .tokenCount(data.getInteger("tokenCount", 0))
                    .error(data.getString("error"))
                    .createdAt(data.getString("createdAt", java.time.Instant.now().toString()))
                    .characterId(data.getString("characterId"))
                    .displayName(displayName)
                    .messageType(data.containsKey("messageType") ? data.getString("messageType") : null)
                        .battleId(data.getString("battleId"))
                    .conversationId(conversationId)
                    .build();

                log.trace("Emitting group character response chunk for character: {}", chunk.getDisplayName());
                emitter.next(chunk);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to group state change events.
     * Schema: groupStateChanged(conversationId: ID!): Conversation! @auth
     */
    @Subscription
    public Publisher<Conversation> groupStateChanged(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "group:" + conversationId + ":state-changed";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.<Conversation>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                Conversation conv = new Conversation();
                conv.setId(UUID.fromString(data.getString("conversationId")));
                conv.setProcessingOnly(data.getBoolean("processingOnly",false));
                if (data.getJsonObject("processingStatus") != null)
                    conv.setProcessingStatus(data.getJsonObject("processingStatus").mapTo(com.eonmux.chatllm.models.ConversationProgress.class));
                // Normalize enum values to UPPER_CASE for GraphQL compatibility
                String groupState = data.getString("groupState");
                conv.setGroupState(groupState != null ? groupState.toUpperCase() : null);
                String turnMode = data.getString("turnMode");
                conv.setTurnMode(turnMode != null ? turnMode.toUpperCase() : null);
                if (data.getString("currentSpeakerId") != null) {
                    conv.setCurrentSpeakerId(UUID.fromString(data.getString("currentSpeakerId")));
                }
                if (data.getString("lastSpeakerId") != null) {
                    conv.setLastSpeakerId(UUID.fromString(data.getString("lastSpeakerId")));
                }
                conv.setConsecutiveResponseCount(data.getInteger("consecutiveResponseCount", 0));

                log.debug("Emitting group state change: conversationId={}, state={}",
                    conv.getId(), conv.getGroupState());
                emitter.next(conv);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to interjection request events.
     * Schema: interjectionRequested(conversationId: ID!): InterjectionRequest! @auth
     */
    @Subscription
    public Publisher<InterjectionRequest> interjectionRequested(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "group:" + conversationId + ":interjection-requested";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.<InterjectionRequest>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                InterjectionRequest ir = new InterjectionRequest();
                ir.setId(UUID.fromString(data.getString("id")));
                ir.setConversationId(UUID.fromString(data.getString("conversationId")));
                if (data.getString("requesterId") != null) {
                    ir.setRequesterId(UUID.fromString(data.getString("requesterId")));
                }
                ir.setRequesterType(data.getString("requesterType"));
                ir.setReason(data.getString("reason"));
                ir.setPriority(data.getInteger("priority", 0));
                ir.setStatus(data.getString("status", "pending"));

                log.debug("Emitting interjection request: id={}, type={}",
                    ir.getId(), ir.getRequesterType());
                emitter.next(ir);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to interjection resolution events.
     * Schema: interjectionResolved(conversationId: ID!): InterjectionRequest! @auth
     */
    @Subscription
    public Publisher<InterjectionRequest> interjectionResolved(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "group:" + conversationId + ":interjection-resolved";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.<InterjectionRequest>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                InterjectionRequest ir = new InterjectionRequest();
                ir.setId(UUID.fromString(data.getString("id")));
                ir.setConversationId(UUID.fromString(data.getString("conversationId")));
                if (data.getString("requesterId") != null) {
                    ir.setRequesterId(UUID.fromString(data.getString("requesterId")));
                }
                ir.setRequesterType(data.getString("requesterType"));
                ir.setStatus(data.getString("status"));

                log.debug("Emitting interjection resolution: id={}, status={}",
                    ir.getId(), ir.getStatus());
                emitter.next(ir);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to character join events in a group.
     * Schema: characterJoined(conversationId: ID!): ConversationCharacter! @auth
     */
    @Subscription
    public Publisher<ConversationCharacter> characterJoined(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "group:" + conversationId + ":character-joined";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.<ConversationCharacter>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                ConversationCharacter cc = new ConversationCharacter();
                cc.setId(UUID.fromString(data.getString("id")));
                cc.setConversationId(UUID.fromString(data.getString("conversationId")));
                cc.setCharacterId(UUID.fromString(data.getString("characterId")));
                cc.setTurnOrder(data.getInteger("turnOrder", 0));
                cc.setIsActive(true);

                log.debug("Emitting character joined: conversationId={}, characterId={}",
                    cc.getConversationId(), cc.getCharacterId());
                emitter.next(cc);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to character leave events in a group.
     * Schema: characterLeft(conversationId: ID!): ID! @auth
     */
    @Subscription
    public Publisher<UUID> characterLeft(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "group:" + conversationId + ":character-left";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.<UUID>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            // Publisher (GroupChatService.publishGroupEvent) emits a flat
            // JsonObject with `characterId`, not a raw String UUID, so we
            // consume JsonObject and pull the UUID field out.
            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();
                String characterIdStr = data != null ? data.getString("characterId") : null;
                if (characterIdStr == null) {
                    log.warn("character-left event missing characterId for conversation {}", conversationId);
                    return;
                }
                UUID characterId = UUID.fromString(characterIdStr);
                log.debug("Emitting character left: conversationId={}, characterId={}",
                    conversationId, characterId);
                emitter.next(characterId);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to turn change events in a group.
     * Schema: turnChanged(conversationId: ID!): Character! @auth
     */
    @Subscription
    public Publisher<com.eonmux.chatllm.models.Character> turnChanged(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        String subscriptionId = UUID.randomUUID().toString();
        String channelName = "group:" + conversationId + ":turn-changed";

        return gatedConversationPublisher(conversationId, userId, () -> Flux.<com.eonmux.chatllm.models.Character>create(emitter -> {
            AtomicBoolean isActive = new AtomicBoolean(true);
            activeStreams.put(subscriptionId, isActive);

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(channelName);
            activeConsumers.put(subscriptionId, consumer);

            consumer.handler(message -> {
                if (!isActive.get()) {
                    return;
                }

                JsonObject data = message.body();

                com.eonmux.chatllm.models.Character character = new com.eonmux.chatllm.models.Character();
                character.setId(UUID.fromString(data.getString("characterId")));
                character.setName(data.getString("characterName"));
                character.setAvatar(data.getString("characterAvatar"));

                log.debug("Emitting turn change: conversationId={}, newCharacter={}",
                    conversationId, character.getName());
                emitter.next(character);
            });

            emitter.onDispose(() -> cleanup(subscriptionId));
        }));
    }

    /**
     * Subscribe to Redis pub/sub channel for distributed events.
     */
    private void subscribeToRedisChannel(String channelName, FluxSink<JsonObject> emitter, String subscriptionId) {
        redisClient.connect()
            .compose(connection -> {
                RedisAPI redis = RedisAPI.api(connection);

                // Set up message handler for this channel
                connection.handler(message -> {
                    if (message != null && message.type() == io.vertx.redis.client.ResponseType.PUSH) {
                        // Parse Redis pub/sub message
                        if (message.size() >= 3) {
                            String channel = message.get(1).toString();
                            String data = message.get(2).toString();

                            if (channelName.equals(channel)) {
                                try {
                                    JsonObject jsonData = new JsonObject(data);
                                    emitter.next(jsonData);
                                } catch (Exception e) {
                                    log.warn("Failed to parse Redis message", e);
                                }
                            }
                        }
                    }
                });

                return redis.subscribe(java.util.List.of(channelName));
            })
            .onSuccess(response -> {
                log.debug("Subscribed to Redis channel: {}", channelName);
            })
            .onFailure(error -> {
                log.error("Failed to subscribe to Redis channel: {}", channelName, error);
            });
    }

    /**
     * Clean up subscription resources.
     */
    private void cleanup(String subscriptionId) {
        // Mark stream as inactive
        AtomicBoolean isActive = activeStreams.remove(subscriptionId);
        if (isActive != null) {
            isActive.set(false);
        }

        // Unregister EventBus consumer
        MessageConsumer<?> consumer = activeConsumers.remove(subscriptionId);
        if (consumer != null) {
            consumer.unregisterAndForget();
        }

        log.debug("Cleaned up subscription: {}", subscriptionId);
    }

    /**
     * Safely get an integer value from a JsonObject, handling Long to Int conversion.
     */
    private int getIntSafe(JsonObject json, String key, int defaultValue) {
        Object value = json.getValue(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * Safely get a double value from a JsonObject.
     */
    private double getDoubleSafe(JsonObject json, String key, double defaultValue) {
        Object value = json.getValue(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }
}