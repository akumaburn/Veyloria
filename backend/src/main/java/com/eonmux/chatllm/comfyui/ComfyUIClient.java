package com.eonmux.chatllm.comfyui;

import com.eonmux.chatllm.config.AppConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class ComfyUIClient {

    private final Vertx vertx;
    private final AppConfig config;
    private WebClient client;
    private final String baseUrl;
    // Scheduler for onTimeout(...).on(scheduler) — the Mutiny request wrapper
    // exposes no per-request send timeout, so request hangs are capped at the
    // Uni level (Mutiny 3: onTimeout(Duration).on(ScheduledExecutorService)).
    private final java.util.concurrent.ScheduledExecutorService timeoutPool =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "comfyui-timeout");
            t.setDaemon(true);
            return t;
        });

    @Inject
    public ComfyUIClient(Vertx vertx, AppConfig config) {
        this.vertx = vertx;
        this.config = config;
        this.baseUrl = "http://" + config.getComfyuiHost() + ":" + config.getComfyuiPort();
        this.client = WebClient.create(vertx, new WebClientOptions()
            .setDefaultHost(config.getComfyuiHost())
            .setDefaultPort(config.getComfyuiPort())
            .setConnectTimeout(10000)
            .setIdleTimeout(300));
    }

    // Default constructor for CDI proxying.
    protected ComfyUIClient() {
        this.vertx = null;
        this.config = null;
        this.baseUrl = null;
    }

    public Uni<String> queuePrompt(JsonObject workflow) {
        String clientId = UUID.randomUUID().toString();
        JsonObject body = new JsonObject()
            .put("prompt", workflow)
            .put("client_id", clientId);

        // A hung /prompt must fail, not block: the queue manager parks the job
        // in its single active slot for the life of the request, so an
        // unbounded submit deadlocks the whole pipeline (observed after a
        // peer restart left a pooled connection half-dead).
        return capTimeout(client.post("/prompt").sendJson(body), 120)
            .map(response -> {
                if (response.statusCode() == 200) {
                    return response.bodyAsJsonObject().getString("prompt_id");
                }
                throw new RuntimeException("ComfyUI queue failed: " + response.statusCode() + " " + response.bodyAsString());
            });
    }

    public Uni<JsonObject> getProgress(String promptId) {
        // Same deadlock class as queuePrompt: a hung poll stalls the
        // recursive poll chain and the iteration cap never advances.
        return capTimeout(client.get("/history/" + promptId).send(), 30)
            .map(response -> {
                if (response.statusCode() == 200) {
                    JsonObject history = response.bodyAsJsonObject();
                    return history.getJsonObject(promptId, new JsonObject());
                }
                return new JsonObject();
            });
    }

    public Uni<List<String>> getOutputImages(String promptId) {
        return getProgress(promptId)
            .map(history -> {
                List<String> images = new ArrayList<>();
                JsonObject outputs = history.getJsonObject("outputs", new JsonObject());
                for (String nodeId : outputs.fieldNames()) {
                    JsonObject nodeOutput = outputs.getJsonObject(nodeId);
                    JsonArray imgs = nodeOutput.getJsonArray("images", new JsonArray());
                    for (int i = 0; i < imgs.size(); i++) {
                        JsonObject img = imgs.getJsonObject(i);
                        String filename = img.getString("filename");
                        String subfolder = img.getString("subfolder", "");
                        String type = img.getString("type", "output");
                        images.add(baseUrl + "/view?filename=" + filename +
                            "&subfolder=" + subfolder + "&type=" + type);
                    }
                }
                return images;
            });
    }

    /**
     * Upload a reference image into ComfyUI's input directory and return the
     * server-side file name for use in a LoadImage node.
     */
    public Uni<String> uploadImage(String filename, byte[] data, String subfolder) {
        // The mutiny web client has no multipart helper, so the form body is
        // assembled by hand: two text fields plus the image file part.
        // Every part is: boundary, headers, blank line, value, CRLF — the
        // final CRLF is mandatory even for empty values, otherwise the next
        // boundary line is parsed as part content and ComfyUI 500s.
        String folder = subfolder == null ? "" : subfolder;
        String boundary = "----veyloria" + java.util.UUID.randomUUID().toString().replace("-", "");
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        Buffer coreBody = Buffer.buffer();
        java.util.function.BiConsumer<String, String> part = (headers, value) -> coreBody
            .appendBytes(("--" + boundary + "\r\n" + headers + "\r\n\r\n" + value + "\r\n").getBytes(utf8));
        part.accept("Content-Disposition: form-data; name=\"subfolder\"", folder);
        part.accept("Content-Disposition: form-data; name=\"type\"", "input");
        coreBody
            .appendBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(utf8))
            .appendBuffer(Buffer.buffer(data))
            .appendBytes(("\r\n--" + boundary + "--\r\n").getBytes(utf8));

        return client.post("/upload/image")
            .addQueryParam("subfolder", folder)
            .addQueryParam("type", "input")
            .putHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
            .sendBuffer(io.vertx.mutiny.core.buffer.Buffer.buffer(coreBody.getBytes()))
            .map(response -> {
                if (response.statusCode() == 200) {
                    return response.bodyAsJsonObject().getString("name");
                }
                throw new RuntimeException("ComfyUI upload failed: " + response.statusCode() + " " + response.bodyAsString());
            });
    }

    /**
     * Download an image (typically a {@code /view?filename=...} output URL
     * from {@link #getOutputImages}) and return its raw bytes. Used to pull
     * finished sheets back for reference-crop derivation.
     */
    public Uni<io.vertx.mutiny.core.buffer.Buffer> downloadImage(String absoluteUrl) {
        return client.requestAbs(io.vertx.core.http.HttpMethod.GET, absoluteUrl)
            .send()
            .map(response -> {
                if (response.statusCode() == 200) {
                    return response.bodyAsBuffer();
                }
                throw new RuntimeException("Image download failed: " + response.statusCode());
            });
    }

    public Uni<JsonObject> getSystemStats() {
        return client.get("/system_stats")
            .send()
            .map(response -> {
                if (response.statusCode() == 200) {
                    return response.bodyAsJsonObject();
                }
                return new JsonObject().put("error", "ComfyUI unreachable");
            })
            .onFailure().recoverWithItem(err -> new JsonObject().put("error", err.getMessage()));
    }

    public Uni<List<String>> getAvailableModels() {
        return client.get("/object_info/CheckpointLoaderSimple")
            .send()
            .map(response -> {
                List<String> models = new ArrayList<>();
                if (response.statusCode() == 200) {
                    try {
                        JsonArray ckpts = response.bodyAsJsonObject()
                            .getJsonObject("CheckpointLoaderSimple")
                            .getJsonObject("input")
                            .getJsonObject("required")
                            .getJsonArray("ckpt_name")
                            .getJsonArray(0);
                        for (int i = 0; i < ckpts.size(); i++) {
                            models.add(ckpts.getString(i));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse checkpoint models: {}", e.getMessage());
                    }
                }
                return models;
            })
            .onFailure().recoverWithItem(err -> new ArrayList<>());
    }

    public Uni<List<String>> getAvailableLoras() {
        return client.get("/object_info/LoraLoader")
            .send()
            .map(response -> {
                List<String> loras = new ArrayList<>();
                if (response.statusCode() == 200) {
                    try {
                        JsonArray loraNames = response.bodyAsJsonObject()
                            .getJsonObject("LoraLoader")
                            .getJsonObject("input")
                            .getJsonObject("required")
                            .getJsonArray("lora_name")
                            .getJsonArray(0);
                        for (int i = 0; i < loraNames.size(); i++) {
                            loras.add(loraNames.getString(i));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse LoRA list: {}", e.getMessage());
                    }
                }
                return loras;
            })
            .onFailure().recoverWithItem(err -> new ArrayList<>());
    }

    public Uni<Boolean> isConnected() {
        return getSystemStats()
            .map(stats -> !stats.containsKey("error"));
    }

    public void close() {
        if (client != null) client.close();
        timeoutPool.shutdownNow();
    }

    /**
     * Fails the source Uni after {@code seconds} without a terminal event.
     * This Mutiny build's {@code Uni} interface exposes no timeout operator,
     * so the (public) {@code UniOnTimeout} group is constructed directly.
     */
    private <T> Uni<T> capTimeout(Uni<T> source, long seconds) {
        return new io.smallrye.mutiny.groups.UniOnTimeout<>(source,
            java.time.Duration.ofSeconds(seconds), timeoutPool).fail();
    }

    @PreDestroy
    void destroy() {
        // Ensure the WebClient is released on application shutdown.
        close();
    }
}
