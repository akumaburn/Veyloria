package com.eonmux.chatllm.llm;

import com.eonmux.chatllm.config.AppConfig;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the llama.cpp server process lifecycle.
 */
@Slf4j
public class LlamaServerManager {

    private static final String PID_FILE_NAME = "llama-server.pid";

    private final AppConfig config;
    // Per-instance overrides for model path and port. These take precedence
    // over config so ModelServerInstance can hand the real CDI-managed config
    // (whose 208 @ConfigProperty fields are properly injected) and still pin
    // a specific model + port per server. Previously ModelServerInstance did
    // `new AppConfig(){}` which bypassed CDI and left ~148 getters returning
    // Java defaults, corrupting launch arguments.
    private final String effectiveModelPath;
    private final int effectivePort;
    private Process serverProcess;
    private volatile long serverPid = -1;  // Store PID separately for reliable access
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isModelLoaded = new AtomicBoolean(false);
    private Thread shutdownHook;
    private java.io.File pidFile;

    public LlamaServerManager(AppConfig config) {
        this(config,
             config != null ? config.getLlmModelPath() : null,
             config != null ? config.getLlmPort() : 0);
    }

    public LlamaServerManager(AppConfig config, String modelPath, int port) {
        this.config = config;
        this.effectiveModelPath = (modelPath != null && !modelPath.isEmpty())
                ? modelPath
                : (config != null ? config.getLlmModelPath() : null);
        this.effectivePort = port > 0 ? port : (config != null ? config.getLlmPort() : 0);
        // Determine PID file location (same directory as the model or temp)
        String modelPathForPid = effectiveModelPath;
        if (modelPathForPid != null && !modelPathForPid.isEmpty()) {
            java.io.File modelDir = new java.io.File(modelPathForPid).getParentFile();
            if (modelDir != null && modelDir.canWrite()) {
                pidFile = new java.io.File(modelDir, PID_FILE_NAME);
            }
        }
        if (pidFile == null) {
            pidFile = new java.io.File(System.getProperty("java.io.tmpdir"), PID_FILE_NAME);
        }
        log.info("PID file location: {}", pidFile.getAbsolutePath());
    }

    /**
     * Write PID to file for recovery after unexpected termination.
     */
    private void writePidFile(long pid) {
        try {
            java.nio.file.Files.writeString(pidFile.toPath(), String.valueOf(pid));
            log.debug("Wrote PID {} to file: {}", pid, pidFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to write PID file: {}", e.getMessage());
        }
    }

    /**
     * Read PID from file (for cleanup of orphaned processes).
     */
    private long readPidFile() {
        if (!pidFile.exists()) {
            return -1;
        }
        try {
            String content = java.nio.file.Files.readString(pidFile.toPath()).trim();
            return Long.parseLong(content);
        } catch (IOException | NumberFormatException e) {
            log.debug("Failed to read PID file: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Verify that a recovered PID actually corresponds to a llama-server process
     * before killing it. Without this guard, a stale PID file written by a
     * previous JVM could point at an unrelated process whose PID has been
     * recycled by the OS — and we'd kill that. Reads /proc/<pid>/cmdline on
     * Linux; on platforms where /proc is absent the check fails closed (returns
     * false) so we never kill a process we can't verify.
     */
    private boolean isLlamaServerPid(long pid) {
        if (pid <= 0) return false;
        java.nio.file.Path cmdline = java.nio.file.Path.of("/proc", String.valueOf(pid), "cmdline");
        if (!java.nio.file.Files.exists(cmdline)) return false;
        try {
            // /proc/<pid>/cmdline uses NUL separators between argv entries
            String raw = java.nio.file.Files.readString(cmdline);
            String normalized = raw.replace('\0', ' ').toLowerCase();
            return normalized.contains("llama-server")
                || normalized.contains("llama_server")
                || normalized.contains(config.getLlmBinaryPath().toLowerCase());
        } catch (IOException e) {
            log.debug("Failed to read cmdline for PID {}: {}", pid, e.getMessage());
            return false;
        }
    }

    /**
     * Delete the PID file.
     */
    private void deletePidFile() {
        try {
            java.nio.file.Files.deleteIfExists(pidFile.toPath());
            log.debug("Deleted PID file: {}", pidFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to delete PID file: {}", e.getMessage());
        }
    }

    /**
     * Kill any orphaned llama-server processes from previous runs.
     * Called before starting a new server.
     */
    private void cleanupOrphanedProcesses() {
        log.info("Checking for orphaned llama-server processes...");

        // First, try to kill process from PID file. Verify the PID actually
        // belongs to a llama-server process before killing — a previous JVM
        // crash could leave a stale PID file pointing at a recycled, unrelated
        // process.
        long oldPid = readPidFile();
        if (oldPid > 0) {
            log.info("Found PID file with PID: {}, checking if process is alive...", oldPid);
            if (isProcessAlive(oldPid)) {
                if (isLlamaServerPid(oldPid)) {
                    log.info("Killing orphaned llama-server from previous run (PID: {})", oldPid);
                    killProcessByPid(oldPid);
                } else {
                    log.warn("PID {} is alive but does not belong to llama-server — skipping kill (stale PID file)", oldPid);
                }
            } else {
                log.debug("Process {} is no longer running", oldPid);
            }
            deletePidFile();
        }

        // Also kill by binary name as fallback (in case PID file was lost)
        killByProcessName();
    }

    /**
     * Check if a process is alive.
     */
    private boolean isProcessAlive(long pid) {
        try {
            ProcessBuilder check = new ProcessBuilder("kill", "-0", String.valueOf(pid));
            Process proc = check.start();
            return proc.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Kill a process by PID using OS-level kill command.
     * More reliable than Java's Process.destroy() for native processes.
     */
    private void killProcessByPid(long pid) {
        if (pid <= 0) {
            return;
        }

        try {
            // First kill any child processes
            log.info("Killing child processes of PID: {}", pid);
            ProcessBuilder pkillChildren = new ProcessBuilder("pkill", "-9", "-P", String.valueOf(pid));
            Process pkillProc = pkillChildren.start();
            pkillProc.waitFor(2, TimeUnit.SECONDS);

            // Check if main process is still alive
            ProcessBuilder checkAlive = new ProcessBuilder("kill", "-0", String.valueOf(pid));
            Process checkProc = checkAlive.start();
            int checkResult = checkProc.waitFor();

            if (checkResult == 0) {
                // Process still exists, try SIGTERM first
                log.info("Sending SIGTERM to PID: {}", pid);
                ProcessBuilder sigterm = new ProcessBuilder("kill", "-15", String.valueOf(pid));
                Process sigtermProc = sigterm.start();
                sigtermProc.waitFor(1, TimeUnit.SECONDS);

                // Give it a moment to terminate gracefully
                Thread.sleep(500);

                // Check again
                checkProc = checkAlive.start();
                checkResult = checkProc.waitFor();

                if (checkResult == 0) {
                    // Still alive, use SIGKILL
                    log.info("Process still alive, sending SIGKILL to PID: {}", pid);
                    ProcessBuilder sigkill = new ProcessBuilder("kill", "-9", String.valueOf(pid));
                    Process sigkillProc = sigkill.start();
                    sigkillProc.waitFor(2, TimeUnit.SECONDS);
                }
            }

            // Final verification
            checkProc = checkAlive.start();
            checkResult = checkProc.waitFor();
            if (checkResult != 0) {
                log.info("Process {} terminated successfully", pid);
            } else {
                log.warn("Process {} may still be running", pid);
            }

        } catch (IOException | InterruptedException e) {
            log.error("Error killing process {}: {}", pid, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Fallback cleanup of any process bound to this server's port, scoped to
     * THIS instance only.
     *
     * CRITICAL: Do NOT `pkill -9 -f <binaryName>`. That matches against the full
     * command line of every process on the host and would SIGKILL all other
     * llama-server instances in the ModelServerPool (and any unrelated
     * llama-server from other applications). Instead we only act on our own PID
     * file (handled in cleanupOrphanedProcesses); this method is now a no-op
     * to preserve the call site without the dangerous name-based kill.
     */
    private void killByProcessName() {
        // Intentionally empty. See Javadoc above.
    }

    /**
     * Register a JVM shutdown hook to kill the llama-server process.
     * This ensures the process is terminated even if the JVM crashes or is killed.
     */
    private void registerShutdownHook() {
        if (shutdownHook != null) {
            return; // Already registered
        }

        shutdownHook = new Thread(() -> {
            long pid = serverPid;  // Capture PID before any state changes
            log.info("JVM shutdown hook triggered for llama-server (PID: {})", pid);

            if (pid > 0) {
                // Use direct OS kill - more reliable than Java Process methods
                killProcessByPid(pid);
            }

            // Fallback: kill by process name in case PID tracking failed
            if (serverProcess != null && serverProcess.isAlive()) {
                log.info("Process still alive after PID kill, using fallback");
                killByProcessName();

                // Last resort: Java's destroyForcibly
                serverProcess.destroyForcibly();
            }

            // Delete PID file
            deletePidFile();

            log.info("Shutdown hook completed");
        }, "llama-server-shutdown-hook");

        Runtime.getRuntime().addShutdownHook(shutdownHook);
        log.info("Registered JVM shutdown hook for llama-server process (PID: {})", serverPid);
    }

    /**
     * Unregister the shutdown hook (called when server is explicitly stopped).
     */
    private void unregisterShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                log.debug("Unregistered llama-server shutdown hook");
            } catch (IllegalStateException e) {
                // JVM is already shutting down, hook can't be removed
                log.debug("Could not remove shutdown hook (JVM shutting down)");
            }
            shutdownHook = null;
        }
    }
    
    /**
     * Start the llama server.
     */
    public Uni<Void> start() {
        if (isRunning.get()) {
            log.info("Llama server is already running");
            return Uni.createFrom().voidItem();
        }

        // If configured to use external server, just verify it's reachable
        if (config.isLlmExternalServer()) {
            log.info("Connecting to external LLM server at {}:{}", config.getLlmHost(),
                    effectivePort > 0 ? effectivePort : config.getLlmPort());
            if (checkServerHealth()) {
                log.info("External LLM server is reachable and healthy");
                isRunning.set(true);
                isModelLoaded.set(true);
                return Uni.createFrom().voidItem();
            } else {
                return Uni.createFrom().failure(new RuntimeException(
                    "External LLM server at " + config.getLlmHost() + ":"
                        + (effectivePort > 0 ? effectivePort : config.getLlmPort()) + " is not reachable"));
            }
        }

        return Uni.createFrom().<Void>emitter(em -> {
            try {
                // Clean up any orphaned processes from previous runs FIRST
                cleanupOrphanedProcesses();

                List<String> command = buildCommand();
                log.info("Starting llama server with command: {}", String.join(" ", command));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(false);

                serverProcess = pb.start();

                // The child's stdin pipe is never used by llama-server; close it
                // immediately to release the OS file descriptor and let the child
                // see EOF on stdin. Previously this FD leaked for the process life.
                try {
                    serverProcess.getOutputStream().close();
                } catch (IOException ignored) { }

                // Store PID immediately for reliable access during shutdown
                serverPid = serverProcess.pid();
                log.info("Llama server process started with PID: {}", serverPid);

                // Write PID to file for recovery after unexpected termination
                writePidFile(serverPid);

                // Register shutdown hook to kill process when JVM exits (best-effort)
                registerShutdownHook();

                // Start output readers
                startOutputReader(serverProcess.getInputStream(), "STDOUT");
                startOutputReader(serverProcess.getErrorStream(), "STDERR");

                // Wait for server to be ready (blocking, runs on worker pool)
                waitForServerReadyBlocking();

                isRunning.set(true);
                log.info("Llama server started successfully");
                em.complete(null);

            } catch (Exception e) {
                // Catch ALL exceptions, not just IOException. waitForServerReadyBlocking()
                // can throw RuntimeException (timeout/interruption) AFTER the process has
                // already been spawned — in that case the process, streams and shutdown
                // hook must be cleaned up so we don't orphan the llama-server.
                log.error("Failed to start llama server: {}", e.getMessage(), e);
                try {
                    cleanupAfterFailedStart();
                } catch (Exception cleanupErr) {
                    log.warn("Error during failed-start cleanup: {}", cleanupErr.getMessage());
                }
                em.fail(e instanceof IOException ? (IOException) e : new RuntimeException(e));
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Tear down any partially-started process/resources when start() fails after
     * the process was spawned. Prevents orphaned llama-server processes.
     */
    private void cleanupAfterFailedStart() {
        if (serverProcess != null && serverProcess.isAlive()) {
            log.info("Killing partially-started llama-server process (PID: {}) after failed start", serverPid);
            if (serverPid > 0) {
                killProcessByPid(serverPid);
            } else {
                serverProcess.destroyForcibly();
            }
        }
        unregisterShutdownHook();
        deletePidFile();
        serverPid = -1;
    }
    
    /**
     * Stop the llama server.
     */
    public Uni<Void> stop() {
        // If using external server, just reset state (don't try to stop it)
        if (config.isLlmExternalServer()) {
            log.info("External LLM server mode - just resetting state (not stopping external server)");
            isRunning.set(false);
            isModelLoaded.set(false);
            return Uni.createFrom().voidItem();
        }

        if (!isRunning.get() && serverPid <= 0) {
            unregisterShutdownHook();
            return Uni.createFrom().voidItem();
        }

        long pid = serverPid;
        log.info("Stopping llama-server process (PID: {})", pid);

        // Use OS-level kill for reliability
        if (pid > 0) {
            killProcessByPid(pid);
        }

        // Fallback: also try Java's destroy methods
        if (serverProcess != null && serverProcess.isAlive()) {
            log.info("Using Java Process methods as fallback");
            serverProcess.destroyForcibly();
            try {
                serverProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Unregister shutdown hook since we've explicitly stopped the server
        unregisterShutdownHook();

        // Delete PID file
        deletePidFile();

        // Reset state
        serverPid = -1;
        isRunning.set(false);
        isModelLoaded.set(false);
        log.info("Llama server stopped");

        return Uni.createFrom().voidItem();
    }
    
    /**
     * Reload the model.
     */
    public Uni<Void> reload() {
        return stop().chain(v -> start());
    }
    
    /**
     * Check if server is running.
     * For external servers, just check the isRunning flag.
     * For managed servers, also verify the process is alive.
     */
    public boolean isRunning() {
        if (config.isLlmExternalServer()) {
            return isRunning.get();
        }
        return isRunning.get() && serverProcess != null && serverProcess.isAlive();
    }
    
    /**
     * Check if model is loaded.
     */
    public boolean isModelLoaded() {
        return isModelLoaded.get();
    }

    /**
     * Get the process ID of the running llama-server, or -1 if not running.
     */
    public long getProcessId() {
        return serverPid;
    }
    
    /**
     * Build command to start llama server.
     * Updated for llama.cpp b5000+ with new options.
     */
    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();

        // Binary and model
        command.add(config.getLlmBinaryPath());
        command.add("--model");
        command.add(effectiveModelPath != null ? effectiveModelPath : config.getLlmModelPath());

        // Server binding
        command.add("--host");
        command.add(config.getLlmHost());
        command.add("--port");
        command.add(String.valueOf(effectivePort > 0 ? effectivePort : config.getLlmPort()));

        // Context and batching
        if (config.getLlmContextSize() > 0) {
            command.add("--ctx-size");
            command.add(String.valueOf(config.getLlmContextSize()));
        }
        command.add("--batch-size");
        command.add(String.valueOf(config.getLlmBatchSize()));
        command.add("--ubatch-size");
        command.add(String.valueOf(config.getLlmUbatchSize()));

        // Threads
        if (config.getLlmThreads() > 0) {
            command.add("--threads");
            command.add(String.valueOf(config.getLlmThreads()));
        }
        if (config.getLlmThreadsHttp() > 0) {
            command.add("--threads-http");
            command.add(String.valueOf(config.getLlmThreadsHttp()));
        }

        // GPU layers
        if (config.getLlmGpuLayers() != 0) {
            command.add("--n-gpu-layers");
            if (config.getLlmGpuLayers() > 0) {
                command.add(String.valueOf(config.getLlmGpuLayers()));
            } else {
                // -1 means offload all layers
                command.add("-1");
            }
        }

        // Flash attention (on|off|auto)
        String flashAttn = config.getLlmFlashAttn();
        if (flashAttn != null && !flashAttn.isEmpty()) {
            command.add("--flash-attn");
            command.add(flashAttn);
        }

        // KV cache types
        String cacheTypeK = config.getLlmCacheTypeK();
        if (cacheTypeK != null && !cacheTypeK.isEmpty() && !cacheTypeK.equals("f16")) {
            command.add("--cache-type-k");
            command.add(cacheTypeK);
        }
        String cacheTypeV = config.getLlmCacheTypeV();
        if (cacheTypeV != null && !cacheTypeV.isEmpty() && !cacheTypeV.equals("f16")) {
            command.add("--cache-type-v");
            command.add(cacheTypeV);
        }

        // Memory mapping
        if (config.isLlmNoMmap()) {
            command.add("--no-mmap");
        }

        // KV offload
        if (!config.isLlmKvOffload()) {
            command.add("--no-kv-offload");
        }

        // Fit to device memory (on|off)
        String fit = config.getLlmFit();
        if (fit != null && !fit.isEmpty()) {
            command.add("--fit");
            command.add(fit);
        }

        // Fit target and minimum context
        if (config.getLlmFitTarget() != 1024) {
            command.add("--fit-target");
            command.add(String.valueOf(config.getLlmFitTarget()));
        }
        if (config.getLlmFitCtx() != 4096) {
            command.add("--fit-ctx");
            command.add(String.valueOf(config.getLlmFitCtx()));
        }

        // Memory locking
        if (config.isLlmMlock()) {
            command.add("--mlock");
        }

        // Weight repacking
        if (!config.isLlmRepack()) {
            command.add("--no-repack");
        }

        // MoE expert weights on CPU (saves VRAM on Mixtral / Qwen3-MoE / etc.)
        if (config.isLlmCpuMoe()) {
            command.add("--cpu-moe");
        }
        if (config.getLlmNCpuMoe() >= 0) {
            command.add("--n-cpu-moe");
            command.add(String.valueOf(config.getLlmNCpuMoe()));
        }

        // Tensor-op offload to GPU (default on; emit --no-op-offload only when disabled).
        if (!config.isLlmOpOffload()) {
            command.add("--no-op-offload");
        }

        // KV cache defragmentation threshold (-1 disables).
        if (config.getLlmDefragThold() >= 0.0f) {
            command.add("--defrag-thold");
            command.add(String.valueOf(config.getLlmDefragThold()));
        }

        // RPC backends (comma-separated host:port list).
        String rpc = config.getLlmRpc();
        if (rpc != null && !rpc.isEmpty()) {
            command.add("--rpc");
            command.add(rpc);
        }

        // Multi-GPU and device selection
        String device = config.getLlmDevice();
        if (device != null && !device.isEmpty()) {
            command.add("--device");
            command.add(device);
        }
        if (config.getLlmMainGpu() != 0) {
            command.add("--main-gpu");
            command.add(String.valueOf(config.getLlmMainGpu()));
        }
        String splitMode = config.getLlmSplitMode();
        if (splitMode != null && !splitMode.isEmpty() && !splitMode.equals("layer")) {
            command.add("--split-mode");
            command.add(splitMode);
        }
        String tensorSplit = config.getLlmTensorSplit();
        if (tensorSplit != null && !tensorSplit.isEmpty()) {
            command.add("--tensor-split");
            command.add(tensorSplit);
        }
        String numa = config.getLlmNuma();
        if (numa != null && !numa.isEmpty()) {
            command.add("--numa");
            command.add(numa);
        }

        // Prompt caching
        if (config.getLlmCacheRam() != 8192) {
            command.add("--cache-ram");
            command.add(String.valueOf(config.getLlmCacheRam()));
        }
        if (config.getLlmCacheReuse() > 0) {
            command.add("--cache-reuse");
            command.add(String.valueOf(config.getLlmCacheReuse()));
        }
        if (config.isLlmKvUnified()) {
            command.add("--kv-unified");
        }
        if (config.getLlmCtxCheckpoints() != 8) {
            command.add("--ctx-checkpoints");
            command.add(String.valueOf(config.getLlmCtxCheckpoints()));
        }
        if (config.isLlmSwaFull()) {
            command.add("--swa-full");
        }

        // Parallel slots
        if (config.getLlmParallelSlots() != 0) {
            command.add("--parallel");
            command.add(String.valueOf(config.getLlmParallelSlots()));
        }

        // Continuous batching
        if (!config.isLlmContBatching()) {
            command.add("--no-cont-batching");
        }

        // Chat template
        String chatTemplate = config.getLlmChatTemplate();
        if (chatTemplate != null && !chatTemplate.isEmpty()) {
            command.add("--chat-template");
            command.add(chatTemplate);
        }

        // Chat template kwargs (e.g., {"reasoning_effort":"high"} for GPT-OSS)
        String chatTemplateKwargs = config.getLlmChatTemplateKwargs();
        if (chatTemplateKwargs != null && !chatTemplateKwargs.isEmpty()) {
            command.add("--chat-template-kwargs");
            command.add(chatTemplateKwargs);
        } else {
            // Auto-detect GPT-OSS and add default kwargs
            String modelPath = effectiveModelPath != null ? effectiveModelPath : config.getLlmModelPath();
            if (modelPath != null && modelPath.toLowerCase().contains("gpt-oss")) {
                String reasoningEffort = config.getLlmGptOssReasoningEffort();
                if (reasoningEffort == null || reasoningEffort.isEmpty()) {
                    reasoningEffort = "high";
                }
                command.add("--chat-template-kwargs");
                command.add("{\"reasoning_effort\":\"" + reasoningEffort + "\"}");
                log.info("Auto-detected GPT-OSS model, adding --chat-template-kwargs with reasoning_effort={}", reasoningEffort);
            }
        }

        // Jinja template engine
        if (!config.isLlmJinja()) {
            command.add("--no-jinja");
        }

        // Reasoning format (none|deepseek|deepseek-legacy|auto)
        String reasoningFormat = config.getLlmReasoningFormat();
        if (reasoningFormat != null && !reasoningFormat.isEmpty() && !reasoningFormat.equals("auto")) {
            command.add("--reasoning-format");
            command.add(reasoningFormat);
        }

        // Reasoning budget
        if (config.getLlmReasoningBudget() != -1) {
            command.add("--reasoning-budget");
            command.add(String.valueOf(config.getLlmReasoningBudget()));
        }

        // Server timeout
        if (config.getLlmTimeout() != 600) {
            command.add("--timeout");
            command.add(String.valueOf(config.getLlmTimeout()));
        }

        // Metrics endpoint
        if (config.isLlmMetrics()) {
            command.add("--metrics");
        }

        // WebUI
        if (!config.isLlmWebui()) {
            command.add("--no-webui");
        }

        // Context shift
        if (config.isLlmContextShift()) {
            command.add("--context-shift");
        }

        // Warmup
        if (!config.isLlmWarmup()) {
            command.add("--no-warmup");
        }

        // Slots endpoint
        if (config.isLlmSlots()) {
            command.add("--slots");
        } else {
            command.add("--no-slots");
        }

        // Props endpoint
        if (config.isLlmProps()) {
            command.add("--props");
        }

        // API key authentication
        String apiKey = config.getLlmApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            command.add("--api-key");
            command.add(apiKey);
        }

        // Slot prompt similarity
        if (config.getLlmSlotPromptSimilarity() != 0.10f) {
            command.add("--slot-prompt-similarity");
            command.add(String.valueOf(config.getLlmSlotPromptSimilarity()));
        }

        // LoRA adapters
        String loraPath = config.getLlmLoraPath();
        if (loraPath != null && !loraPath.isEmpty()) {
            command.add("--lora");
            command.add(loraPath);
        }
        String loraScaled = config.getLlmLoraScaled();
        if (loraScaled != null && !loraScaled.isEmpty()) {
            command.add("--lora-scaled");
            command.add(loraScaled);
        }

        // RoPE scaling
        String ropeScaling = config.getLlmRopeScaling();
        if (ropeScaling != null && !ropeScaling.isEmpty()) {
            command.add("--rope-scaling");
            command.add(ropeScaling);
        }
        if (config.getLlmRopeScale() > 0) {
            command.add("--rope-scale");
            command.add(String.valueOf(config.getLlmRopeScale()));
        }
        if (config.getLlmRopeFreqBase() > 0) {
            command.add("--rope-freq-base");
            command.add(String.valueOf(config.getLlmRopeFreqBase()));
        }
        if (config.getLlmRopeFreqScale() > 0) {
            command.add("--rope-freq-scale");
            command.add(String.valueOf(config.getLlmRopeFreqScale()));
        }

        // YaRN parameters
        if (config.getLlmYarnOrigCtx() > 0) {
            command.add("--yarn-orig-ctx");
            command.add(String.valueOf(config.getLlmYarnOrigCtx()));
        }
        if (config.getLlmYarnExtFactor() >= 0) {
            command.add("--yarn-ext-factor");
            command.add(String.valueOf(config.getLlmYarnExtFactor()));
        }
        if (config.getLlmYarnAttnFactor() >= 0) {
            command.add("--yarn-attn-factor");
            command.add(String.valueOf(config.getLlmYarnAttnFactor()));
        }
        if (config.getLlmYarnBetaSlow() >= 0) {
            command.add("--yarn-beta-slow");
            command.add(String.valueOf(config.getLlmYarnBetaSlow()));
        }
        if (config.getLlmYarnBetaFast() >= 0) {
            command.add("--yarn-beta-fast");
            command.add(String.valueOf(config.getLlmYarnBetaFast()));
        }

        // Speculative decoding (draft model)
        String modelDraft = config.getLlmModelDraft();
        if (modelDraft != null && !modelDraft.isEmpty()) {
            command.add("--model-draft");
            command.add(modelDraft);

            if (config.getLlmGpuLayersDraft() != -1) {
                command.add("--n-gpu-layers-draft");
                command.add(String.valueOf(config.getLlmGpuLayersDraft()));
            }
            if (config.getLlmDraftMax() != 16) {
                command.add("--draft-max");
                command.add(String.valueOf(config.getLlmDraftMax()));
            }
            if (config.getLlmDraftMin() > 0) {
                command.add("--draft-min");
                command.add(String.valueOf(config.getLlmDraftMin()));
            }
            if (config.getLlmDraftPMin() != 0.8f) {
                command.add("--draft-p-min");
                command.add(String.valueOf(config.getLlmDraftPMin()));
            }
            if (config.getLlmDraftPSplit() != 0.1f) {
                command.add("--draft-p-split");
                command.add(String.valueOf(config.getLlmDraftPSplit()));
            }
            if (config.getLlmCtxSizeDraft() > 0) {
                command.add("--ctx-size-draft");
                command.add(String.valueOf(config.getLlmCtxSizeDraft()));
            }
            String ctkDraft = config.getLlmCacheTypeKDraft();
            if (ctkDraft != null && !ctkDraft.isEmpty()) {
                command.add("--cache-type-k-draft");
                command.add(ctkDraft);
            }
            String ctvDraft = config.getLlmCacheTypeVDraft();
            if (ctvDraft != null && !ctvDraft.isEmpty()) {
                command.add("--cache-type-v-draft");
                command.add(ctvDraft);
            }
            String deviceDraft = config.getLlmDeviceDraft();
            if (deviceDraft != null && !deviceDraft.isEmpty()) {
                command.add("--device-draft");
                command.add(deviceDraft);
            }
            if (config.getLlmThreadsDraft() > 0) {
                command.add("--threads-draft");
                command.add(String.valueOf(config.getLlmThreadsDraft()));
            }
            if (config.getLlmThreadsBatchDraft() > 0) {
                command.add("--threads-batch-draft");
                command.add(String.valueOf(config.getLlmThreadsBatchDraft()));
            }
            if (config.isLlmCpuMoeDraft()) {
                command.add("--cpu-moe-draft");
            }
            if (config.getLlmNCpuMoeDraft() >= 0) {
                command.add("--n-cpu-moe-draft");
                command.add(String.valueOf(config.getLlmNCpuMoeDraft()));
            }
        }

        // Extra server features
        appendExtraServerArgs(command);

        return command;
    }
    
    /**
     * Emit the long tail of llama-server flags that don't have natural homes
     * in the structured sections of buildCommand(): server-feature toggles,
     * multi-modal projector, advanced sampling, CPU affinity, batch threading,
     * and LoRA-load-without-apply. Keeping this as a separate method avoids
     * making buildCommand() unreadable.
     */
    private void appendExtraServerArgs(List<String> command) {
        // Server features
        String alias = config.getLlmAlias();
        if (alias != null && !alias.isEmpty()) {
            command.add("--alias");
            command.add(alias);
        }
        String apiKeyFile = config.getLlmApiKeyFile();
        if (apiKeyFile != null && !apiKeyFile.isEmpty()) {
            command.add("--api-key-file");
            command.add(apiKeyFile);
        }
        String apiPrefix = config.getLlmApiPrefix();
        if (apiPrefix != null && !apiPrefix.isEmpty()) {
            command.add("--api-prefix");
            command.add(apiPrefix);
        }
        String slotSavePath = config.getLlmSlotSavePath();
        if (slotSavePath != null && !slotSavePath.isEmpty()) {
            command.add("--slot-save-path");
            command.add(slotSavePath);
        }
        if (config.isLlmReusePort()) {
            command.add("--reuse-port");
        }
        if (config.isLlmNoHost()) {
            command.add("--no-host");
        }
        if (config.isLlmOffline()) {
            command.add("--offline");
        }
        // prefill-assistant defaults to true upstream; emit --no-prefill-assistant only when off.
        if (!config.isLlmPrefillAssistant()) {
            command.add("--no-prefill-assistant");
        }
        String chatTemplateFile = config.getLlmChatTemplateFile();
        if (chatTemplateFile != null && !chatTemplateFile.isEmpty()) {
            command.add("--chat-template-file");
            command.add(chatTemplateFile);
        }
        String sslCert = config.getLlmSslCertFile();
        if (sslCert != null && !sslCert.isEmpty()) {
            command.add("--ssl-cert-file");
            command.add(sslCert);
        }
        String sslKey = config.getLlmSslKeyFile();
        if (sslKey != null && !sslKey.isEmpty()) {
            command.add("--ssl-key-file");
            command.add(sslKey);
        }

        // Multi-modal (vision) projector
        String mmproj = config.getLlmMmproj();
        if (mmproj != null && !mmproj.isEmpty()) {
            command.add("--mmproj");
            command.add(mmproj);
        }
        String mmprojUrl = config.getLlmMmprojUrl();
        if (mmprojUrl != null && !mmprojUrl.isEmpty()) {
            command.add("--mmproj-url");
            command.add(mmprojUrl);
        }
        if (!config.isLlmMmprojAuto()) {
            // both spellings are accepted upstream; --no-mmproj-auto is the canonical form
            command.add("--no-mmproj-auto");
        }
        if (!config.isLlmMmprojOffload()) {
            command.add("--no-mmproj-offload");
        }

        // Sampling-time generation knobs
        if (config.getLlmSeed() != -1) {
            command.add("--seed");
            command.add(String.valueOf(config.getLlmSeed()));
        }
        if (config.getLlmKeep() != -1) {
            command.add("--keep");
            command.add(String.valueOf(config.getLlmKeep()));
        }
        if (config.isLlmIgnoreEos()) {
            command.add("--ignore-eos");
        }
        if (config.getLlmDynatempRange() != 0.0f) {
            command.add("--dynatemp-range");
            command.add(String.valueOf(config.getLlmDynatempRange()));
        }
        if (config.getLlmDynatempExp() != 1.0f) {
            command.add("--dynatemp-exp");
            command.add(String.valueOf(config.getLlmDynatempExp()));
        }

        // Advanced / constrained sampling
        String logitBias = config.getLlmLogitBias();
        if (logitBias != null && !logitBias.isEmpty()) {
            command.add("--logit-bias");
            command.add(logitBias);
        }
        String grammar = config.getLlmGrammar();
        if (grammar != null && !grammar.isEmpty()) {
            command.add("--grammar");
            command.add(grammar);
        }
        String grammarFile = config.getLlmGrammarFile();
        if (grammarFile != null && !grammarFile.isEmpty()) {
            command.add("--grammar-file");
            command.add(grammarFile);
        }
        String jsonSchema = config.getLlmJsonSchema();
        if (jsonSchema != null && !jsonSchema.isEmpty()) {
            command.add("--json-schema");
            command.add(jsonSchema);
        }
        String jsonSchemaFile = config.getLlmJsonSchemaFile();
        if (jsonSchemaFile != null && !jsonSchemaFile.isEmpty()) {
            command.add("--json-schema-file");
            command.add(jsonSchemaFile);
        }
        String drySeqBreaker = config.getLlmDrySequenceBreaker();
        if (drySeqBreaker != null && !drySeqBreaker.isEmpty()) {
            command.add("--dry-sequence-breaker");
            command.add(drySeqBreaker);
        }

        // CPU affinity & scheduling
        if (config.getLlmThreadsBatch() > 0) {
            command.add("--threads-batch");
            command.add(String.valueOf(config.getLlmThreadsBatch()));
        }
        String cpuMask = config.getLlmCpuMask();
        if (cpuMask != null && !cpuMask.isEmpty()) {
            command.add("--cpu-mask");
            command.add(cpuMask);
        }
        String cpuRange = config.getLlmCpuRange();
        if (cpuRange != null && !cpuRange.isEmpty()) {
            command.add("--cpu-range");
            command.add(cpuRange);
        }
        if (config.getLlmCpuStrict() != 0) {
            command.add("--cpu-strict");
            command.add(String.valueOf(config.getLlmCpuStrict()));
        }
        if (config.getLlmPrio() != 0) {
            command.add("--prio");
            command.add(String.valueOf(config.getLlmPrio()));
        }
        if (config.getLlmPoll() != 50) {
            command.add("--poll");
            command.add(String.valueOf(config.getLlmPoll()));
        }
        String cpuMaskBatch = config.getLlmCpuMaskBatch();
        if (cpuMaskBatch != null && !cpuMaskBatch.isEmpty()) {
            command.add("--cpu-mask-batch");
            command.add(cpuMaskBatch);
        }
        String cpuRangeBatch = config.getLlmCpuRangeBatch();
        if (cpuRangeBatch != null && !cpuRangeBatch.isEmpty()) {
            command.add("--cpu-range-batch");
            command.add(cpuRangeBatch);
        }
        if (config.getLlmCpuStrictBatch() >= 0) {
            command.add("--cpu-strict-batch");
            command.add(String.valueOf(config.getLlmCpuStrictBatch()));
        }
        if (config.getLlmPrioBatch() >= 0) {
            command.add("--prio-batch");
            command.add(String.valueOf(config.getLlmPrioBatch()));
        }
        if (config.getLlmPollBatch() >= 0) {
            command.add("--poll-batch");
            command.add(String.valueOf(config.getLlmPollBatch()));
        }

        // LoRA load-without-apply (clients enable per-request)
        if (config.isLlmLoraInitWithoutApply()) {
            command.add("--lora-init-without-apply");
        }
    }

    /**
     * Calculate GPU layers based on model size and available VRAM.
     */
    private int calculateGpuLayers() {
        // This is a simplified calculation
        // In production, you would query actual GPU memory
        
        java.io.File modelFile = new java.io.File(effectiveModelPath != null ? effectiveModelPath : config.getLlmModelPath());
        if (!modelFile.exists()) {
            return 0;
        }
        
        long modelSize = modelFile.length();
        long modelSizeGB = modelSize / (1024L * 1024L * 1024L);
        
        // Assume we have 8GB VRAM available (simplified)
        long availableVRAM = 8;
        
        if (modelSizeGB <= 4) {
            return 35; // Small model, offload all layers
        } else if (modelSizeGB <= 8) {
            return 20; // Medium model, partial offload
        } else {
            return 10; // Large model, minimal offload
        }
    }
    
    /**
     * Start output reader thread.
     */
    private void startOutputReader(java.io.InputStream stream, String prefix) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.debug("[{}] {}", prefix, line);
                    
                    // Check for model loaded message
                    if (line.contains("llama server listening") || 
                        line.contains("model loaded") ||
                        line.contains("server listening")) {
                        isModelLoaded.set(true);
                    }
                }
            } catch (IOException e) {
                log.error("Error reading {} stream", prefix, e);
            }
        });
        reader.setDaemon(true);
        reader.start();
    }
    
    /**
     * Wait for server to be ready (blocking, called from worker pool).
     */
    private void waitForServerReadyBlocking() {
        int attempts = 0;
        int maxAttempts = 120; // 120 seconds timeout

        while (attempts < maxAttempts) {
            if (isModelLoaded.get() || checkServerHealth()) {
                isModelLoaded.set(true);
                return;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for llama server", e);
            }

            attempts++;
        }

        throw new RuntimeException("Timeout waiting for llama server to be ready");
    }
    
    /**
     * Check server health via HTTP.
     */
    private boolean checkServerHealth() {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(
                "http://" + config.getLlmHost() + ":" + (effectivePort > 0 ? effectivePort : config.getLlmPort()) + "/health"
            );

            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);

            int responseCode = conn.getResponseCode();
            return responseCode == 200;

        } catch (Exception e) {
            return false;
        } finally {
            // Always disconnect so the underlying socket is released, including
            // when getResponseCode() throws. Previously a thrown response left
            // the connection leaked (up to ~120 sockets during a failed startup).
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }
        }
    }
}