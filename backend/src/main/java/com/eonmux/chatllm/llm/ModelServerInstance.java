package com.eonmux.chatllm.llm;

import com.eonmux.chatllm.config.AppConfig;
import io.smallrye.mutiny.Uni;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a single llama-server instance serving a specific model.
 * Tracks active users, usage statistics, and server lifecycle.
 */
@Slf4j
@Getter
public class ModelServerInstance {

    private final String modelPath;
    private final int port;
    private final LlamaServerManager serverManager;
    private final Set<String> activeUsers = ConcurrentHashMap.newKeySet();
    private volatile Instant lastUsedAt;
    private volatile Instant startedAt;
    private volatile boolean isStarting = false;
    private final AtomicBoolean isStartingCAS = new AtomicBoolean(false);

    public ModelServerInstance(String modelPath, int port, AppConfig baseConfig) {
        this.modelPath = modelPath;
        this.port = port;

        // Pass the REAL CDI-managed baseConfig (whose 208 @ConfigProperty fields
        // are properly injected) plus this instance's model path/port overrides.
        // Previously this did `new AppConfig(){ ... }` which created a non-CDI
        // instance and left ~148 getters returning Java defaults (0/false/null),
        // corrupting launch arguments like --seed, --keep, --no-op-offload, etc.
        this.serverManager = new LlamaServerManager(baseConfig, modelPath, port);
        this.lastUsedAt = Instant.now();
    }

    /**
     * Start this model server instance.
     */
    public Uni<Void> start() {
        // Use an AtomicBoolean-style guard to make the check-then-act atomic.
        // Previously a concurrent caller that saw isStarting==true got a
        // successful voidItem() instead of waiting/failing.
        if (!isStartingCAS.compareAndSet(false, true)) {
            log.debug("Server for model {} is already starting", getModelName());
            // The in-flight start is responsible for completing; wait until it
            // finishes (or fails) instead of returning success immediately.
            return awaitStartCompletion();
        }

        startedAt = Instant.now();
        log.info("Starting model server instance: model={}, port={}", getModelName(), port);

        return serverManager.start()
                .onItem().invoke(v -> {
                    isStarting = false;
                    isStartingCAS.set(false);
                    log.info("Model server started successfully: model={}, port={}", getModelName(), port);
                })
                .onFailure().invoke(err -> {
                    isStarting = false;
                    isStartingCAS.set(false);
                    log.error("Failed to start model server: model={}, port={}", getModelName(), port, err);
                });
    }

    /**
     * Wait for an in-flight start (initiated by a concurrent caller) to settle.
     * Polls the shared CAS flag so the caller only completes once the starter
     * has actually finished, and propagates a timeout instead of hanging.
     */
    private Uni<Void> awaitStartCompletion() {
        return Uni.createFrom().deferred(() -> {
            long deadline = System.currentTimeMillis() + 120_000;
            return pollStartState(deadline);
        });
    }

    private Uni<Void> pollStartState(long deadline) {
        if (!isStartingCAS.get()) {
            return Uni.createFrom().voidItem();
        }
        if (System.currentTimeMillis() > deadline) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "Timed out waiting for model server to start: " + getModelName()));
        }
        return Uni.createFrom().item(null)
                .onItem().delayIt().by(java.time.Duration.ofMillis(100))
                .chain(v -> pollStartState(deadline));
    }

    /**
     * Stop this model server instance.
     */
    public Uni<Void> stop() {
        log.info("Stopping model server instance: model={}, port={}, activeUsers={}",
                getModelName(), port, activeUsers.size());
        return serverManager.stop();
    }

    /**
     * Register a user as actively using this model server.
     * Null or empty userIds are converted to "anonymous" to prevent NPE.
     */
    public void registerUser(String userId) {
        String effectiveUserId = (userId == null || userId.isEmpty()) ? "anonymous" : userId;
        activeUsers.add(effectiveUserId);
        lastUsedAt = Instant.now();
        log.debug("User {} registered to model server {} (total users: {})",
                effectiveUserId, getModelName(), activeUsers.size());
    }

    /**
     * Unregister a user from this model server.
     * Null or empty userIds are converted to "anonymous" to match registration.
     */
    public void unregisterUser(String userId) {
        String effectiveUserId = (userId == null || userId.isEmpty()) ? "anonymous" : userId;
        activeUsers.remove(effectiveUserId);
        lastUsedAt = Instant.now();
        log.debug("User {} unregistered from model server {} (remaining users: {})",
                effectiveUserId, getModelName(), activeUsers.size());
    }

    /**
     * Check if this server has any active users.
     */
    public boolean hasActiveUsers() {
        return !activeUsers.isEmpty();
    }

    /**
     * Get the number of active users.
     */
    public int getActiveUserCount() {
        return activeUsers.size();
    }

    /**
     * Check if server is running.
     */
    public boolean isRunning() {
        return serverManager.isRunning();
    }

    /**
     * Check if model is loaded.
     */
    public boolean isModelLoaded() {
        return serverManager.isModelLoaded();
    }

    /**
     * Get idle time in seconds.
     */
    public long getIdleSeconds() {
        if (lastUsedAt == null) {
            return 0;
        }
        return Instant.now().getEpochSecond() - lastUsedAt.getEpochSecond();
    }

    /**
     * Get uptime in seconds.
     */
    public long getUptimeSeconds() {
        if (startedAt == null) {
            return 0;
        }
        return Instant.now().getEpochSecond() - startedAt.getEpochSecond();
    }

    /**
     * Get model name from path (filename without extension).
     */
    public String getModelName() {
        if (modelPath == null || modelPath.isEmpty()) {
            return "unknown";
        }
        String filename = new java.io.File(modelPath).getName();
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    }

    /**
     * Get server connection URL.
     */
    public String getServerUrl() {
        return "http://127.0.0.1:" + port;
    }

    @Override
    public String toString() {
        return String.format("ModelServerInstance{model=%s, port=%d, users=%d, idle=%ds}",
                getModelName(), port, getActiveUserCount(), getIdleSeconds());
    }
}
