package com.eonmux.chatllm.comfyui;

import com.eonmux.chatllm.config.AppConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
@Slf4j
public class ComfyUIQueueManager {

    private final Vertx vertx;
    private final AppConfig config;
    private final ComfyUIClient client;
    private final Queue<QueueItem> pendingQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, QueueItem> activeJobs = new ConcurrentHashMap<>();
    private final Map<String, Integer> pollCounters = new ConcurrentHashMap<>();
    private final Map<String, Long> activePollTimers = new ConcurrentHashMap<>();

    // Limit concurrent jobs to 1 (serial processing). Previously processQueue
    // polled an item on every submit with no concurrency guard, so concurrent
    // submits could run multiple jobs at once against a single-GPU ComfyUI.
    private static final int MAX_CONCURRENT_JOBS = 1;
    // Hard cap on poll iterations so a job that never completes (ComfyUI crash,
    // eviction, network) doesn't poll forever. At 2s/poll this is ~10 minutes.
    private static final int MAX_POLL_ITERATIONS = 300;
    private static final int TOTAL_STEPS = 20;

    @Inject
    public ComfyUIQueueManager(Vertx vertx, AppConfig config, ComfyUIClient client) {
        this.vertx = vertx;
        this.config = config;
        this.client = client;
    }

    // Default constructor for CDI proxying.
    protected ComfyUIQueueManager() {
        this.vertx = null;
        this.config = null;
        this.client = null;
    }

    @PostConstruct
    void init() {
        log.info("ComfyUIQueueManager initialized (maxQueueSize={})", config.getComfyuiMaxQueueSize());
    }

    @Data
    public static class QueueItem {
        private String jobId;
        private String promptId;
        private String conversationId;
        private String characterId;
        private JsonObject workflow;
        // Noise seed captured from the submitted graph at submit time;
        // carried on the completion event so the consumer's
        // COALESCE(?, seed) preserves the row's seed even when it is 0.
        private Long seed;
        private String status; // "pending", "queued", "processing", "complete", "error"
        private Float progress;
        private String resultUrl;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
    }

    public Uni<String> submit(String conversationId, String characterId, JsonObject workflow) {
        // Mint a job ID; callers that need to correlate with a DB row should use
        // the overload below so the completion event carries their own ID.
        return submitWithId(java.util.UUID.randomUUID().toString(), conversationId, characterId, workflow);
    }

    /**
     * Submit using a caller-supplied job ID. Use this when the caller already
     * has a primary key (e.g. the generated_image.imageId) so the
     * {@code comfyui.job.complete} event's {@code jobId} matches that row,
     * allowing the completion consumer to UPDATE the correct record.
     */
    public Uni<String> submitWithId(String jobId, String conversationId, String characterId, JsonObject workflow) {
        if (pendingQueue.size() + activeJobs.size() >= config.getComfyuiMaxQueueSize()) {
            return Uni.createFrom().failure(new RuntimeException("Image generation queue is full (max " + config.getComfyuiMaxQueueSize() + ")"));
        }

        QueueItem item = new QueueItem();
        item.setJobId(jobId);
        item.setConversationId(conversationId);
        item.setCharacterId(characterId);
        item.setWorkflow(workflow);
        item.setSeed(extractSeed(workflow));
        item.setStatus("pending");
        item.setProgress(0f);
        item.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));

        pendingQueue.add(item);
        processQueue();
        return Uni.createFrom().item(item.getJobId());
    }

    public Uni<QueueItem> getStatus(String jobId) {
        // Check active jobs
        QueueItem active = activeJobs.get(jobId);
        if (active != null) return Uni.createFrom().item(active);

        // Check pending queue
        for (QueueItem item : pendingQueue) {
            if (item.getJobId().equals(jobId)) return Uni.createFrom().item(item);
        }

        return Uni.createFrom().failure(new RuntimeException("Job not found: " + jobId));
    }

    public int getQueueSize() {
        return pendingQueue.size() + activeJobs.size();
    }

    private void processQueue() {
        if (pendingQueue.isEmpty()) return;
        // Enforce a concurrency cap so a single-GPU ComfyUI isn't overwhelmed.
        // Previously there was no limit, so concurrent submits ran in parallel.
        if (activeJobs.size() >= MAX_CONCURRENT_JOBS) return;

        QueueItem item = pendingQueue.poll();
        if (item == null) return;

        item.setStatus("queued");
        activeJobs.put(item.getJobId(), item);

        client.queuePrompt(item.getWorkflow())
            .subscribe().with(
                promptId -> {
                    item.setPromptId(promptId);
                    item.setStatus("processing");
                    log.info("ComfyUI job {} queued as prompt {}", item.getJobId(), promptId);
                    publishProgress(item.getConversationId(), item.getJobId(), 0.0, 0, TOTAL_STEPS, "processing");
                    startPolling(item);
                },
                err -> {
                    // Centralized cleanup: frees the slot, drains the queue,
                    // and publishes comfyui.job.failed so the resolver can
                    // mark the row `failed` (an inline cleanup here predated
                    // failJob and leaked pollCounters + the event).
                    failJob(item, err.getMessage());
                });
    }

    private void startPolling(QueueItem item) {
        long timerId = vertx.setPeriodic(2000, id -> {
            if (item.getPromptId() == null) return;

            // Bounded polling: give up after a maximum number of iterations so a
            // job that never completes (ComfyUI crash/eviction/network) doesn't
            // poll forever and leak the timer + activeJobs slot.
            Integer count = pollCounters.get(item.getJobId());
            if (count != null && count >= MAX_POLL_ITERATIONS) {
                vertx.cancelTimer(id);
                activePollTimers.remove(item.getJobId());
                failJob(item, "ComfyUI generation timed out after " + (MAX_POLL_ITERATIONS * 2) + "s");
                return;
            }

            client.getProgress(item.getPromptId())
                .subscribe().with(
                    progress -> {
                        JsonObject status = progress.getJsonObject("status", new JsonObject());
                        boolean completed = status.getBoolean("completed", false) ||
                            progress.containsKey("outputs");

                        if (completed) {
                            vertx.cancelTimer(id);
                            activePollTimers.remove(item.getJobId());
                            item.setStatus("complete");
                            item.setProgress(1.0f);
                            item.setCompletedAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
                            pollCounters.remove(item.getJobId());

                            publishProgress(item.getConversationId(), item.getJobId(), 1.0, TOTAL_STEPS, TOTAL_STEPS, "complete");

                            // Get output images
                            client.getOutputImages(item.getPromptId())
                                .subscribe().with(
                                    images -> {
                                        if (!images.isEmpty()) {
                                            item.setResultUrl(images.get(0));
                                        }
                                        activeJobs.remove(item.getJobId());

                                        // Publish completion event. jobId is the
                                        // caller-correlated ID (imageId) when
                                        // submitWithId was used. seed carries the
                                        // noise seed captured at submit time for
                                        // reproducibility; null when the graph had
                                        // no recognizable seed node, in which case
                                        // the consumer's COALESCE keeps the row's
                                        // seed.
                                        vertx.eventBus().publish("comfyui.job.complete",
                                            new JsonObject()
                                                .put("jobId", item.getJobId())
                                                .put("conversationId", item.getConversationId())
                                                .put("resultUrl", item.getResultUrl())
                                                .put("seed", item.getSeed()));

                                        processQueue();
                                    },
                                    err -> {
                                        log.warn("Failed to get output images: {}", err.getMessage());
                                        activeJobs.remove(item.getJobId());
                                        processQueue();
                                    });
                        } else {
                            // Estimate progress based on poll count (rough approximation)
                            int c = pollCounters.merge(item.getJobId(), 1, Integer::sum);
                            int currentStep = Math.min(c, TOTAL_STEPS);
                            double estimatedProgress = Math.min((double) currentStep / TOTAL_STEPS, 0.95);
                            publishProgress(item.getConversationId(), item.getJobId(), estimatedProgress, currentStep, TOTAL_STEPS, "running");
                        }
                    },
                    err -> {
                        // Fail the job, cancel its timer, free the active slot,
                        // and drain the queue. Previously the timer kept firing
                        // on a dead job forever and the rest of the queue stalled.
                        vertx.cancelTimer(id);
                        activePollTimers.remove(item.getJobId());
                        failJob(item, "ComfyUI progress polling failed: " + err.getMessage());
                    });
        });
        activePollTimers.put(item.getJobId(), timerId);
    }

    /**
     * Read the noise seed from a submitted workflow graph — a JsonObject
     * keyed by node ID, the shape the builder emits and {@code queuePrompt}
     * sends. Handles both KSampler's `seed` and custom samplers'
     * `noise_seed` (MiniMax H3 RandomNoise).
     *
     * <p>ComfyUI's {@code /history/{id}} entries do NOT record the submitted
     * graph (their {@code "prompt"} field is
     * {@code [queueNum, promptId, extraData, meta, outputNodeIds]}), so the
     * seed is captured from the in-hand graph at submit time and carried on
     * the completion event. This method is total: malformed input yields 0
     * instead of throwing, because a throw in the submit path would lose the
     * whole job.
     */
    static long extractSeed(JsonObject graph) {
        if (graph == null) return 0L;
        try {
            for (String nodeId : graph.fieldNames()) {
                JsonObject node = graph.getJsonObject(nodeId);
                if (node == null) continue;
                JsonObject inputs = node.getJsonObject("inputs");
                if (inputs == null) continue;
                if (inputs.containsKey("noise_seed")) return inputs.getLong("noise_seed", 0L);
                if ("KSampler".equals(node.getString("class_type", "")) && inputs.containsKey("seed")) {
                    return inputs.getLong("seed", 0L);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Seed extraction failed on workflow graph: {}", e.getMessage());
        }
        return 0L;
    }

    /**
     * Mark a job as failed, publish a failed progress event, free its slot, and
     * advance the queue. Centralizes cleanup so every failure path (enqueue
     * failure, poll error, poll timeout) leaves the manager in a consistent
     * state instead of leaking a timer or stalling the queue.
     */
    private void failJob(QueueItem item, String reason) {
        item.setStatus("error");
        activeJobs.remove(item.getJobId());
        pollCounters.remove(item.getJobId());
        publishProgress(item.getConversationId(), item.getJobId(), 0.0, 0, TOTAL_STEPS, "failed");
        log.warn("ComfyUI job {} failed: {}", item.getJobId(), reason);
        // The DB row was inserted `pending` by the resolver; without this event
        // a queuePrompt rejection (peer 400, node missing, ...) leaves it
        // `pending` forever. The ImageResolver consumer marks it `failed`.
        if (item.getJobId() != null) {
            vertx.eventBus().publish("comfyui.job.failed", new JsonObject()
                .put("jobId", item.getJobId())
                .put("conversationId", item.getConversationId())
                .put("reason", reason));
        }
        processQueue();
    }

    private void publishProgress(String conversationId, String jobId, double progress, int currentStep, int totalSteps, String status) {
        if (conversationId == null) return;
        JsonObject event = new JsonObject()
            .put("promptId", jobId)
            .put("conversationId", conversationId)
            .put("progress", progress)
            .put("currentStep", currentStep)
            .put("totalSteps", totalSteps)
            .put("status", status);
        vertx.eventBus().publish("rp.image.progress." + conversationId, event);
    }

    @PreDestroy
    public void stop() {
        activePollTimers.values().forEach(vertx::cancelTimer);
        activePollTimers.clear();
    }
}
