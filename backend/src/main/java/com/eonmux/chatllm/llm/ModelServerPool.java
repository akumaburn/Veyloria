package com.eonmux.chatllm.llm;

import com.eonmux.chatllm.config.AppConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a pool of llama-server instances, each serving a different model.
 * Provides dynamic model loading based on user preferences with resource management.
 *
 * Architecture:
 * - Each model gets its own llama-server process on a unique port
 * - Multiple users can share the same model server
 * - Servers are started on-demand when first requested
 * - Idle servers are stopped after timeout to free resources
 * - Maximum concurrent servers enforced to prevent resource exhaustion
 */
@Slf4j
public class ModelServerPool {

    private final Vertx vertx;
    private final AppConfig baseConfig;

    // Pool of active model servers, keyed by model path
    private final Map<String, ModelServerInstance> activeServers = new ConcurrentHashMap<>();

    // In-flight server-startup Unis, keyed by model path. Memoized so concurrent callers
    // for the same model path share the same start operation rather than racing.
    private final Map<String, Uni<ModelServerInstance>> startingUnis = new ConcurrentHashMap<>();

    // Port allocation (start from base port + 100, increment by 1)
    private final AtomicInteger nextPort;

    // Ports released from stopped servers, ready for reuse to avoid unbounded nextPort growth.
    private final ConcurrentLinkedQueue<Integer> releasedPorts = new ConcurrentLinkedQueue<>();

    // Upper bound for port allocation (prevents overflow / port exhaustion).
    private final int maxPort;

    // Configuration
    private final int maxConcurrentServers;
    private final long idleTimeoutSeconds;
    private final int basePort;

    // Cleanup timer
    private Long cleanupTimerId;

    public ModelServerPool(Vertx vertx, AppConfig baseConfig) {
        this(vertx, baseConfig, 3, 300); // Default: max 3 servers, 5min idle timeout
    }

    public ModelServerPool(Vertx vertx, AppConfig baseConfig, int maxConcurrentServers, long idleTimeoutSeconds) {
        this.vertx = vertx;
        this.baseConfig = baseConfig;
        this.maxConcurrentServers = maxConcurrentServers;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.basePort = baseConfig.getLlmPort();
        this.nextPort = new AtomicInteger(basePort + 100);
        // Cap at basePort + 65000 (well below TCP max of 65535 even for very high basePorts;
        // allocatePort will throw when this bound is reached instead of silently overflowing).
        this.maxPort = Math.min(basePort + 65000, 65535);

        log.info("Initialized ModelServerPool: maxServers={}, idleTimeout={}s, basePort={}",
                maxConcurrentServers, idleTimeoutSeconds, basePort);

        // Start periodic cleanup task
        startCleanupTask();
    }

    /**
     * Get or create a model server for the specified model path.
     * If server doesn't exist, creates and starts it (if within limits).
     * Thread-safe: uses ConcurrentHashMap.computeIfAbsent + memoized Unis to prevent
     * duplicate server creation WITHOUT blocking the calling thread across the async chain.
     *
     * @param modelPath Path to the model file
     * @param userId User requesting this model (for tracking). Null/empty is converted to "anonymous".
     * @return Future with the model server instance
     */
    public Uni<ModelServerInstance> getOrCreateServer(String modelPath, String userId) {
        // Null or empty model path means use default
        if (modelPath == null || modelPath.isEmpty()) {
            modelPath = baseConfig.getLlmModelPath();
        }

        // Normalize null/empty userId to "anonymous" for tracking
        final String effectiveUserId = (userId == null || userId.isEmpty()) ? "anonymous" : userId;
        final String finalModelPath = modelPath;

        // Fast path: server already fully created and running.
        ModelServerInstance existingServer = activeServers.get(finalModelPath);
        if (existingServer != null) {
            log.debug("Reusing existing model server for user {}: {}", effectiveUserId, existingServer);
            existingServer.registerUser(effectiveUserId);

            // If server is still in its starting phase, wait for readiness.
            if (existingServer.isStarting()) {
                return waitForServerReady(existingServer, userId);
            }

            return Uni.createFrom().item(existingServer);
        }

        // Slow path: need to start a new server. Use computeIfAbsent to atomically
        // memoize the in-flight Uni so concurrent callers for the same model share
        // one startup rather than each trying to create a fresh server.
        Uni<ModelServerInstance> startUni = startingUnis.computeIfAbsent(finalModelPath, path -> {
            // Enforce concurrent-server limit before allocating a port / registering.
            if (activeServers.size() >= maxConcurrentServers) {
                log.warn("Model server limit reached ({}/{}). Attempting to free idle servers.",
                        activeServers.size(), maxConcurrentServers);

                // Try to stop idle servers (fire-and-forget, but entries are removed synchronously).
                stopIdleServers();

                // Check again after cleanup.
                if (activeServers.size() >= maxConcurrentServers) {
                    // Return a failing Uni; no state mutated, so nothing to clean up.
                    return Uni.createFrom().<ModelServerInstance>failure(new RuntimeException(
                        "Cannot start new model server: limit of " + maxConcurrentServers +
                        " concurrent servers reached. All servers are in use."
                    )).memoize().indefinitely();
                }
            }

            final int port;
            try {
                port = allocatePort();
            } catch (RuntimeException e) {
                return Uni.createFrom().<ModelServerInstance>failure(e).memoize().indefinitely();
            }

            final ModelServerInstance newServer = new ModelServerInstance(path, port, baseConfig);

            log.info("Creating new model server: model={}, port={}", newServer.getModelName(), port);

            // Register the instance BEFORE starting so `activeServers.get(path)` by a
            // concurrent caller sees it and takes the fast path into waitForServerReady.
            activeServers.put(path, newServer);

            return newServer.start()
                    .chain(v -> {
                        log.info("Model server started successfully: {}", newServer);
                        return Uni.createFrom().item(newServer);
                    })
                    .onFailure().recoverWithUni(err -> {
                        log.error("Failed to start model server: model={}", path, err);
                        // Cleanup on failure so a subsequent call can retry.
                        activeServers.remove(path);
                        releasedPorts.offer(port);
                        return Uni.createFrom().<ModelServerInstance>failure(err);
                    })
                    // Memoize so concurrent subscribers see the same result without
                    // re-running the startup chain.
                    .memoize().indefinitely();
        });

        // Register this user against the memoized startup and ensure the entry is
        // removed from startingUnis after it settles (so future failures can retry).
        return startUni
                .onItemOrFailure().invoke((server, err) -> startingUnis.remove(finalModelPath))
                .onItem().invoke(server -> {
                    if (server != null) {
                        server.registerUser(effectiveUserId);
                        log.info("Model server ready for user {}: {}", effectiveUserId, server);
                    }
                });
    }

    /**
     * Wait for a server that is currently starting to become ready.
     * Uses an AtomicBoolean guard so that whichever of the periodic poll / timeout fires
     * first wins; the loser is a no-op (prevents IllegalStateException on double-complete).
     */
    private Uni<ModelServerInstance> waitForServerReady(ModelServerInstance server, String userId) {
        return Uni.createFrom().emitter(em -> {
            AtomicBoolean completed = new AtomicBoolean(false);
            long[] timeoutTimerId = {-1};

            // Poll for server readiness
            long timerId = vertx.setPeriodic(500, id -> {
                if (!server.isStarting()) {
                    vertx.cancelTimer(id);
                    // Cancel the timeout timer too so it doesn't fire into an
                    // already-completed emitter and linger for 60s.
                    if (timeoutTimerId[0] >= 0) {
                        vertx.cancelTimer(timeoutTimerId[0]);
                    }
                    if (completed.compareAndSet(false, true)) {
                        if (server.isRunning() && server.isModelLoaded()) {
                            em.complete(server);
                        } else {
                            em.fail(new RuntimeException("Server failed to start properly"));
                        }
                    }
                }
            });

            // Timeout after 60 seconds
            timeoutTimerId[0] = vertx.setTimer(60000, id -> {
                vertx.cancelTimer(timerId);
                if (completed.compareAndSet(false, true)) {
                    em.fail(new RuntimeException("Timeout waiting for server to start"));
                }
            });
        });
    }

    /**
     * Unregister a user from a model server.
     * Called when user's session ends or switches models.
     */
    public void unregisterUser(String modelPath, String userId) {
        if (modelPath == null || modelPath.isEmpty()) {
            modelPath = baseConfig.getLlmModelPath();
        }

        // Normalize null/empty userId to "anonymous" to match registration
        String effectiveUserId = (userId == null || userId.isEmpty()) ? "anonymous" : userId;

        ModelServerInstance server = activeServers.get(modelPath);
        if (server != null) {
            server.unregisterUser(effectiveUserId);
            log.debug("Unregistered user {} from model server: {}", effectiveUserId, server);
        }
    }

    /**
     * Restart a crashed or unhealthy server for the given model path.
     * Stops and removes the dead server, then re-creates it via getOrCreateServer.
     *
     * @param modelPath Path to the model file
     * @return Future with the restarted server instance
     */
    public Uni<ModelServerInstance> restartServer(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            modelPath = baseConfig.getLlmModelPath();
        }

        final String finalModelPath = modelPath;
        ModelServerInstance deadServer = activeServers.remove(finalModelPath);

        Uni<Void> cleanup;
        if (deadServer != null) {
            final int deadPort = deadServer.getPort();
            log.info("Removing dead server from pool before restart: model={}, port={}",
                    deadServer.getModelName(), deadPort);
            cleanup = deadServer.stop()
                .onItemOrFailure().invoke((v, err) -> {
                    // Recycle the port after the stop settles (success or failure) so it can be reused.
                    releasedPorts.offer(deadPort);
                    if (err != null) {
                        log.warn("Error stopping dead server during restart: {}", err.getMessage());
                    }
                })
                .onFailure().recoverWithUni(err -> Uni.createFrom().voidItem());
        } else {
            cleanup = Uni.createFrom().voidItem();
        }

        return cleanup.chain(v -> {
            log.info("Re-creating server for model: {}", finalModelPath);
            return getOrCreateServer(finalModelPath, "auto-recovery");
        });
    }

    /**
     * Stop and remove a specific model server.
     * Recycles the server's port into the released-port pool after the stop settles.
     */
    public Uni<Void> stopServer(String modelPath) {
        ModelServerInstance server = activeServers.remove(modelPath);
        if (server == null) {
            return Uni.createFrom().voidItem();
        }

        final int port = server.getPort();
        log.info("Stopping model server: {}", server);
        return server.stop()
                .onItemOrFailure().invoke((v, err) -> releasedPorts.offer(port));
    }

    /**
     * Stop all idle servers (no active users).
     * Each stop is actually subscribed so the underlying process is terminated,
     * and the port is recycled once the stop settles.
     */
    public void stopIdleServers() {
        activeServers.entrySet().removeIf(entry -> {
            final String path = entry.getKey();
            final ModelServerInstance server = entry.getValue();
            if (!server.hasActiveUsers()) {
                final int port = server.getPort();
                log.info("Stopping idle server: {}", server);
                server.stop().subscribe().with(
                        v -> {
                            releasedPorts.offer(port);
                            log.info("Stopped idle server: {}", path);
                        },
                        err -> {
                            releasedPorts.offer(port);
                            log.error("Failed to stop idle server: {}", path, err);
                        }
                );
                return true;
            }
            return false;
        });
    }

    /**
     * Stop all model servers in the pool.
     * Recycles each server's port into the released-port pool after its stop settles.
     */
    public Uni<Void> stopAll() {
        log.info("Stopping all model servers in pool (total: {})", activeServers.size());

        // Stop cleanup task
        if (cleanupTimerId != null) {
            vertx.cancelTimer(cleanupTimerId);
            cleanupTimerId = null;
        }

        // Stop all servers; capture port per-uni so we can recycle once each stop settles.
        java.util.List<Uni<Void>> unis = activeServers.values().stream()
                .map(server -> {
                    final int port = server.getPort();
                    return server.stop()
                            .onItemOrFailure().invoke((v, err) -> releasedPorts.offer(port));
                })
                .collect(java.util.stream.Collectors.toList());

        if (unis.isEmpty()) {
            activeServers.clear();
            return Uni.createFrom().voidItem();
        }

        return Uni.combine().all().unis(unis).discardItems()
                .onItem().invoke(() -> {
                    activeServers.clear();
                    log.info("All model servers stopped successfully");
                })
                .onFailure().invoke(err -> {
                    activeServers.clear();
                    log.error("Some model servers failed to stop", err);
                });
    }

    /**
     * Allocate a unique port for a new server instance.
     * Prefers recycling a previously released port; otherwise advances the counter.
     * Throws if the counter has reached maxPort (port exhaustion).
     */
    private int allocatePort() {
        Integer recycled = releasedPorts.poll();
        if (recycled != null) {
            return recycled;
        }
        int port = nextPort.getAndIncrement();
        if (port > maxPort) {
            // Roll the counter back so it doesn't drift unbounded on repeated failures.
            nextPort.set(maxPort + 1);
            throw new RuntimeException(
                "Cannot allocate port for new model server: port range exhausted (max=" + maxPort +
                "). All recycled ports have been consumed."
            );
        }
        return port;
    }

    /**
     * Start periodic cleanup task to stop idle servers.
     * Each stop is actually subscribed and the port is recycled after it settles.
     */
    private void startCleanupTask() {
        // Run cleanup every 60 seconds
        cleanupTimerId = vertx.setPeriodic(60000, timerId -> {
            log.debug("Running idle server cleanup task");

            activeServers.entrySet().removeIf(entry -> {
                final String path = entry.getKey();
                final ModelServerInstance server = entry.getValue();

                // Only consider servers without active users
                if (!server.hasActiveUsers() && server.getIdleSeconds() >= idleTimeoutSeconds) {
                    final int port = server.getPort();
                    log.info("Stopping idle server (idle for {}s): {}",
                            server.getIdleSeconds(), server);
                    server.stop().subscribe().with(
                            v -> {
                                releasedPorts.offer(port);
                                log.info("Stopped idle server: {}", path);
                            },
                            err -> {
                                releasedPorts.offer(port);
                                log.error("Failed to stop idle server: {}", path, err);
                            }
                    );
                    return true;
                }

                return false;
            });
        });
    }

    /**
     * Get pool statistics.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("activeServers", activeServers.size());
        stats.put("maxServers", maxConcurrentServers);
        stats.put("idleTimeoutSeconds", idleTimeoutSeconds);

        int totalUsers = activeServers.values().stream()
                .mapToInt(ModelServerInstance::getActiveUserCount)
                .sum();
        stats.put("totalActiveUsers", totalUsers);

        // Per-server details
        Map<String, Object> servers = new ConcurrentHashMap<>();
        activeServers.forEach((modelPath, server) -> {
            Map<String, Object> serverInfo = new ConcurrentHashMap<>();
            serverInfo.put("modelName", server.getModelName());
            serverInfo.put("port", server.getPort());
            serverInfo.put("activeUsers", server.getActiveUserCount());
            serverInfo.put("idleSeconds", server.getIdleSeconds());
            serverInfo.put("uptimeSeconds", server.getUptimeSeconds());
            serverInfo.put("running", server.isRunning());
            servers.put(server.getModelName(), serverInfo);
        });
        stats.put("servers", servers);

        return stats;
    }

    /**
     * Get default model server (for backwards compatibility).
     * Returns the server for the default model path from config.
     * Will create and start the server if it doesn't exist.
     */
    public Uni<ModelServerInstance> getDefaultServer(String userId) {
        return getOrCreateServer(baseConfig.getLlmModelPath(), userId);
    }

    /**
     * Get existing server for a model path WITHOUT creating one.
     * Use this for status checks to avoid unnecessary server starts.
     *
     * @param modelPath Path to the model file (null/empty for default)
     * @return The existing server instance, or null if not running
     */
    public ModelServerInstance getExistingServer(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            modelPath = baseConfig.getLlmModelPath();
        }
        return activeServers.get(modelPath);
    }

    /**
     * Get the default server if it exists, without creating one.
     * Use this for status checks to avoid unnecessary server starts.
     *
     * @return The existing default server instance, or null if not running
     */
    public ModelServerInstance getExistingDefaultServer() {
        return getExistingServer(baseConfig.getLlmModelPath());
    }

    /**
     * Check if pool has capacity for new servers.
     */
    public boolean hasCapacity() {
        return activeServers.size() < maxConcurrentServers;
    }

    /**
     * Get number of active servers.
     */
    public int getActiveServerCount() {
        return activeServers.size();
    }
}
