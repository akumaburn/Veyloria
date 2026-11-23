package com.eonmux.chatllm.services;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes user presence (online/offline) to Redis and the Vert.x event bus.
 *
 * Redis key: {@code presence:<userId>} (TTL 60 s, renewed by heartbeat)
 * Event bus channels:
 *   {@code user:<userId>:presence}  -- per-user presenceUpdates subscription
 *   {@code global:user:presence}    -- userPresence subscription (all changes)
 */
@Slf4j
@ApplicationScoped
public class UserPresenceService {

    @Inject
    Redis redisClient;

    @Inject
    Vertx vertx;

    public void updatePresence(String userId, String status) {
        String key = "presence:" + userId;
        JsonObject presence = new JsonObject()
            .put("userId", userId)
            .put("status", status)
            .put("lastSeen", java.time.Instant.now().toString());

        redisClient.connect()
            .compose(conn -> RedisAPI.api(conn).setex(key, "60", presence.encode()))
            .onSuccess(r -> {
                vertx.eventBus().publish("user:" + userId + ":presence", presence);
                vertx.eventBus().publish("global:user:presence", presence);
            })
            .onFailure(err ->
                log.warn("Failed to update presence for user {}: {}", userId, err.getMessage()));
    }
}
