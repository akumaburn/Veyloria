package com.eonmux.chatllm.messaging;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.Message;
import io.vertx.mutiny.core.eventbus.MessageConsumer;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Event-bus → Redis bridge. Replaces the deleted RedisVerticle by registering
 * CDI-managed consumers for the same addresses the producers still publish to.
 *
 * <p>All handlers mirror the old contract so existing callers (SettingsService,
 * CacheHelper, UserService, MutationResolver, etc.) continue to work unchanged.
 */
@Slf4j
@ApplicationScoped
public class RedisCacheBridge {

    @Inject
    Vertx vertx;

    @Inject
    RedisAPI redis;

    // Kept so shutdown removes this instance from the bus. Dev-mode reloads
    // otherwise leave stale consumers that still receive round-robin traffic.
    private final List<MessageConsumer<JsonObject>> consumers = new ArrayList<>();

    void onStart(@Observes StartupEvent ev) {
        var bus = vertx.eventBus();

        consumers.add(bus.<JsonObject>consumer("redis.get").handler(this::handleGet));
        consumers.add(bus.<JsonObject>consumer("redis.set").handler(this::handleSet));
        consumers.add(bus.<JsonObject>consumer("redis.delete").handler(this::handleDelete));
        consumers.add(bus.<JsonObject>consumer("redis.exists").handler(this::handleExists));
        consumers.add(bus.<JsonObject>consumer("redis.expire").handler(this::handleExpire));
        consumers.add(bus.<JsonObject>consumer("redis.publish").handler(this::handlePublish));
        consumers.add(bus.<JsonObject>consumer("redis.health").handler(this::handleHealth));

        consumers.add(bus.<JsonObject>consumer("redis.session.get").handler(this::handleSessionGet));
        consumers.add(bus.<JsonObject>consumer("redis.session.set").handler(this::handleSessionSet));
        consumers.add(bus.<JsonObject>consumer("redis.session.delete").handler(this::handleSessionDelete));
        consumers.add(bus.<JsonObject>consumer("redis.session.refresh").handler(this::handleSessionRefresh));

        consumers.add(bus.<JsonObject>consumer("redis.cache.get").handler(this::handleCacheGet));
        consumers.add(bus.<JsonObject>consumer("redis.cache.set").handler(this::handleCacheSet));
        consumers.add(bus.<JsonObject>consumer("redis.cache.invalidate").handler(this::handleCacheInvalidate));

        log.info("Redis event-bus bridge ready ({} consumers registered)", consumers.size());
    }

    void onStop(@Observes ShutdownEvent ev) {
        synchronized (consumers) {
            for (MessageConsumer<JsonObject> consumer : consumers) {
                try {
                    consumer.unregister().await().atMost(java.time.Duration.ofSeconds(5));
                } catch (Exception error) {
                    log.warn("Failed to unregister Redis consumer {}", consumer.address(), error);
                }
            }
            consumers.clear();
        }
    }

    // ---------- Basic ops ----------

    private void handleGet(Message<JsonObject> msg) {
        String key = msg.body().getString("key");
        if (key == null) { msg.fail(400, "Key is required"); return; }
        redis.get(key).subscribe().with(
                response -> msg.reply(new JsonObject()
                        .put("value", response != null ? response.toString() : null)),
                err -> fail(msg, "Redis GET failed for " + key, err));
    }

    private void handleSet(Message<JsonObject> msg) {
        JsonObject body = msg.body();
        String key = body.getString("key");
        String value = body.getString("value");
        Integer ttl = body.getInteger("ttl");
        if (key == null || value == null) { msg.fail(400, "Key and value are required"); return; }

        List<String> args = new ArrayList<>();
        args.add(key);
        args.add(value);
        if (ttl != null && ttl > 0) {
            args.add("EX");
            args.add(ttl.toString());
        }
        redis.set(args).subscribe().with(
                response -> msg.reply(new JsonObject().put("success", true)),
                err -> fail(msg, "Redis SET failed for " + key, err));
    }

    private void handleDelete(Message<JsonObject> msg) {
        String key = msg.body().getString("key");
        if (key == null) { msg.fail(400, "Key is required"); return; }
        redis.del(List.of(key)).subscribe().with(
                response -> msg.reply(new JsonObject().put("deleted", asInt(response))),
                err -> fail(msg, "Redis DEL failed for " + key, err));
    }

    private void handleExists(Message<JsonObject> msg) {
        String key = msg.body().getString("key");
        if (key == null) { msg.fail(400, "Key is required"); return; }
        redis.exists(List.of(key)).subscribe().with(
                response -> msg.reply(new JsonObject().put("exists", asInt(response) > 0)),
                err -> fail(msg, "Redis EXISTS failed for " + key, err));
    }

    private void handleExpire(Message<JsonObject> msg) {
        JsonObject body = msg.body();
        String key = body.getString("key");
        Integer ttl = body.getInteger("ttl");
        if (key == null || ttl == null) { msg.fail(400, "Key and TTL are required"); return; }
        redis.expire(List.of(key, ttl.toString())).subscribe().with(
                response -> msg.reply(new JsonObject().put("success", asInt(response) > 0)),
                err -> fail(msg, "Redis EXPIRE failed for " + key, err));
    }

    private void handlePublish(Message<JsonObject> msg) {
        JsonObject body = msg.body();
        String channel = body.getString("channel");
        String data = body.getString("message");
        if (channel == null || data == null) { msg.fail(400, "Channel and message are required"); return; }
        redis.publish(channel, data).subscribe().with(
                response -> msg.reply(new JsonObject()
                        .put("success", true)
                        .put("subscribers", asInt(response))),
                err -> fail(msg, "Redis PUBLISH failed for " + channel, err));
    }

    private void handleHealth(Message<JsonObject> msg) {
        redis.ping(List.of()).subscribe().with(
                response -> msg.reply(new JsonObject()
                        .put("healthy", true)
                        .put("response", response != null ? response.toString() : "")
                        .put("timestamp", LocalDateTime.now(ZoneOffset.UTC).toString())),
                err -> msg.reply(new JsonObject()
                        .put("healthy", false)
                        .put("error", err.getMessage())
                        .put("timestamp", LocalDateTime.now(ZoneOffset.UTC).toString())));
    }

    // ---------- Session ops ----------

    private void handleSessionGet(Message<JsonObject> msg) {
        String sessionId = msg.body().getString("sessionId");
        if (sessionId == null) { msg.fail(400, "Session ID is required"); return; }
        redis.get("session:" + sessionId).subscribe().with(
                response -> {
                    if (response == null) { msg.reply(new JsonObject()); return; }
                    try {
                        msg.reply(new JsonObject(response.toString()));
                    } catch (Exception e) {
                        msg.reply(new JsonObject());
                    }
                },
                err -> fail(msg, "Redis session GET failed for " + sessionId, err));
    }

    private void handleSessionSet(Message<JsonObject> msg) {
        JsonObject body = msg.body();
        String sessionId = body.getString("sessionId");
        JsonObject session = body.getJsonObject("session");
        Integer ttl = body.getInteger("ttl", 3600);
        if (sessionId == null || session == null) {
            msg.fail(400, "Session ID and session data are required");
            return;
        }
        redis.setex("session:" + sessionId, String.valueOf(ttl), session.encode()).subscribe().with(
                response -> msg.reply(new JsonObject().put("success", true)),
                err -> fail(msg, "Redis session SET failed for " + sessionId, err));
    }

    private void handleSessionDelete(Message<JsonObject> msg) {
        String sessionId = msg.body().getString("sessionId");
        if (sessionId == null) { msg.fail(400, "Session ID is required"); return; }
        redis.del(List.of("session:" + sessionId)).subscribe().with(
                response -> msg.reply(new JsonObject().put("deleted", asInt(response) > 0)),
                err -> fail(msg, "Redis session DEL failed for " + sessionId, err));
    }

    private void handleSessionRefresh(Message<JsonObject> msg) {
        JsonObject body = msg.body();
        String sessionId = body.getString("sessionId");
        Integer ttl = body.getInteger("ttl", 3600);
        if (sessionId == null) { msg.fail(400, "Session ID is required"); return; }
        redis.expire(List.of("session:" + sessionId, ttl.toString())).subscribe().with(
                response -> msg.reply(new JsonObject().put("refreshed", asInt(response) > 0)),
                err -> fail(msg, "Redis session EXPIRE failed for " + sessionId, err));
    }

    // ---------- Cache ops ----------

    private void handleCacheGet(Message<JsonObject> msg) {
        String cacheKey = msg.body().getString("key");
        if (cacheKey == null) { msg.fail(400, "Cache key is required"); return; }
        redis.get("cache:" + cacheKey).subscribe().with(
                response -> {
                    if (response == null) { msg.reply(new JsonObject().putNull("data")); return; }
                    try {
                        msg.reply(new JsonObject(response.toString()));
                    } catch (Exception e) {
                        msg.reply(new JsonObject().putNull("data"));
                    }
                },
                err -> fail(msg, "Redis cache GET failed for " + cacheKey, err));
    }

    private void handleCacheSet(Message<JsonObject> msg) {
        JsonObject body = msg.body();
        String cacheKey = body.getString("key");
        JsonObject data = body.getJsonObject("data");
        Integer ttl = body.getInteger("ttl", 300);
        if (cacheKey == null || data == null) {
            msg.fail(400, "Cache key and data are required");
            return;
        }
        redis.setex("cache:" + cacheKey, String.valueOf(ttl), data.encode()).subscribe().with(
                response -> msg.reply(new JsonObject().put("success", true)),
                err -> fail(msg, "Redis cache SET failed for " + cacheKey, err));
    }

    private void handleCacheInvalidate(Message<JsonObject> msg) {
        String pattern = msg.body().getString("pattern");
        if (pattern == null) { msg.fail(400, "Pattern is required"); return; }

        String keyPattern = "cache:" + pattern;
        scanAndDelete(keyPattern, "0", new ArrayList<>(), 0).subscribe().with(
                deleted -> msg.reply(new JsonObject()
                        .put("success", true)
                        .put("invalidated", deleted)),
                err -> fail(msg, "Redis cache invalidation failed for " + pattern, err));
    }

    // Hard cap on SCAN iterations so a stuck/buggy cursor can't recurse without
    // bound. With COUNT=100 and ~50k keys per scan window this is plenty;
    // anything beyond that points at a Redis-side problem worth surfacing.
    private static final int MAX_SCAN_ITERATIONS = 1000;

    /**
     * Recursively scan and delete keys matching a pattern using Redis SCAN.
     * Non-blocking alternative to KEYS — safe for production.
     */
    private Uni<Integer> scanAndDelete(String pattern, String cursor, List<String> foundKeys, int iterations) {
        if (iterations >= MAX_SCAN_ITERATIONS) {
            log.warn("Redis SCAN aborted after {} iterations for pattern {} — cursor may be stuck", iterations, pattern);
            if (foundKeys.isEmpty()) return Uni.createFrom().item(0);
            return redis.del(foundKeys).map(RedisCacheBridge::asInt);
        }
        return redis.scan(List.of(cursor, "MATCH", pattern, "COUNT", "100"))
                .chain(scanResponse -> {
                    if (scanResponse == null || scanResponse.size() < 2) {
                        return Uni.createFrom().item(0);
                    }
                    String nextCursor = scanResponse.get(0).toString();
                    Response keysResponse = scanResponse.get(1);
                    if (keysResponse != null && keysResponse.size() > 0) {
                        for (Response key : keysResponse) {
                            foundKeys.add(key.toString());
                        }
                    }
                    if ("0".equals(nextCursor)) {
                        if (foundKeys.isEmpty()) {
                            return Uni.createFrom().item(0);
                        }
                        return redis.del(foundKeys).map(RedisCacheBridge::asInt);
                    }
                    return scanAndDelete(pattern, nextCursor, foundKeys, iterations + 1);
                });
    }

    // ---------- Helpers ----------

    private static int asInt(Response response) {
        return response != null ? response.toInteger() : 0;
    }

    private void fail(Message<JsonObject> msg, String context, Throwable err) {
        log.error("{}: {}", context, err.getMessage(), err);
        msg.fail(500, err.getMessage());
    }
}
