package com.eonmux.chatllm.utils;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

/**
 * Generic cache helper to consolidate caching logic across services.
 * Eliminates duplicate caching patterns by providing type-safe operations.
 *
 * @param <T> The entity type to cache
 */
@Slf4j
public class CacheHelper<T> {

    private final EventBus eventBus;
    private final String cachePrefix;
    private final int defaultTtl;
    private final Class<T> entityClass;

    /**
     * Create a new CacheHelper.
     *
     * @param eventBus    The Vert.x event bus
     * @param cachePrefix The prefix for cache keys (e.g., "character", "conversation")
     * @param defaultTtl  Default TTL in seconds
     * @param entityClass The class of the entity type
     */
    public CacheHelper(EventBus eventBus, String cachePrefix, int defaultTtl, Class<T> entityClass) {
        this.eventBus = eventBus;
        this.cachePrefix = cachePrefix;
        this.defaultTtl = defaultTtl;
        this.entityClass = entityClass;
    }

    /**
     * Build a cache key from an ID.
     *
     * @param id The entity ID
     * @return The full cache key
     */
    public String buildKey(String id) {
        return cachePrefix + ":" + id;
    }

    /**
     * Get an entity from cache.
     *
     * @param cacheKey The cache key
     * @return Future with the cached entity or null if not found
     */
    public Uni<T> get(String cacheKey) {
        return eventBus.<JsonObject>request("redis.cache.get", new JsonObject().put("key", cacheKey))
            .map(reply -> {
                JsonObject result = reply.body();
                if (result != null && result.getValue("data") != null) {
                    try {
                        JsonObject data = result.getJsonObject("data");
                        return data.mapTo(entityClass);
                    } catch (Exception e) {
                        log.debug("Failed to deserialize cached entity for key {}: {}", cacheKey, e.getMessage());
                        return null;
                    }
                }
                return null;
            })
            .onFailure().recoverWithItem(error -> {
                log.debug("Cache miss for key {}: {}", cacheKey, error.getMessage());
                return null;
            });
    }

    /**
     * Store an entity in cache with default TTL.
     *
     * @param cacheKey The cache key
     * @param entity   The entity to cache
     */
    public void set(String cacheKey, T entity) {
        set(cacheKey, entity, defaultTtl);
    }

    /**
     * Store an entity in cache with custom TTL.
     *
     * @param cacheKey The cache key
     * @param entity   The entity to cache
     * @param ttl      TTL in seconds
     */
    public void set(String cacheKey, T entity, int ttl) {
        try {
            eventBus.send("redis.cache.set", new JsonObject()
                .put("key", cacheKey)
                .put("data", JsonObject.mapFrom(entity))
                .put("ttl", ttl));
        } catch (Exception e) {
            log.warn("Failed to cache entity for key {}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * Invalidate cache entries matching a pattern.
     *
     * @param id The entity ID to invalidate
     */
    public void invalidate(String id) {
        String pattern = cachePrefix + ":" + id + "*";
        eventBus.send("redis.cache.invalidate", new JsonObject()
            .put("pattern", pattern));
    }

    /**
     * Invalidate cache entries with a custom pattern.
     *
     * @param pattern The pattern to match (without prefix)
     */
    public void invalidatePattern(String pattern) {
        eventBus.send("redis.cache.invalidate", new JsonObject()
            .put("pattern", pattern));
    }

    /**
     * Get entity from cache or load from database.
     * This is the recommended method for most use cases.
     *
     * @param id       The entity ID
     * @param dbLoader Function to load entity from database if not cached
     * @return Future with the entity
     */
    public Uni<T> getOrLoad(String id, Function<String, Uni<T>> dbLoader) {
        String cacheKey = buildKey(id);

        return get(cacheKey)
            .chain(cached -> {
                if (cached != null) {
                    return Uni.createFrom().item(cached);
                }

                return dbLoader.apply(id)
                    .map(entity -> {
                        if (entity != null) {
                            set(cacheKey, entity);
                        }
                        return entity;
                    });
            });
    }

    /**
     * Invalidate cache with confirmation (waits for response).
     * Use this when cache consistency is critical.
     *
     * @param id The entity ID to invalidate
     * @return Future that completes when invalidation is confirmed
     */
    public Uni<Void> invalidateConfirmed(String id) {
        String pattern = cachePrefix + ":" + id + "*";

        io.vertx.core.eventbus.DeliveryOptions opts =
            new io.vertx.core.eventbus.DeliveryOptions().setSendTimeout(5000);

        return eventBus.request("redis.cache.invalidate",
                new JsonObject().put("pattern", pattern), opts)
            .replaceWithVoid()
            .onItem().invoke(() -> log.debug("Cache invalidated for {}: {}", cachePrefix, id))
            .onFailure().recoverWithUni(error -> {
                log.warn("Cache invalidation timeout for {}: {}. Error: {}",
                    cachePrefix, id, error.getMessage());
                // Complete anyway - don't fail operation due to cache issues
                return Uni.createFrom().voidItem();
            });
    }
}
