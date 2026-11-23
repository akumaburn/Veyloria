package com.eonmux.chatllm.comfyui;

import com.eonmux.chatllm.config.AppConfig;
import io.smallrye.mutiny.Uni;
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

        return client.post("/prompt")
            .sendJson(body)
            .map(response -> {
                if (response.statusCode() == 200) {
                    return response.bodyAsJsonObject().getString("prompt_id");
                }
                throw new RuntimeException("ComfyUI queue failed: " + response.statusCode() + " " + response.bodyAsString());
            });
    }

    public Uni<JsonObject> getProgress(String promptId) {
        return client.get("/history/" + promptId)
            .send()
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
    }

    @PreDestroy
    void destroy() {
        // Ensure the WebClient is released on application shutdown.
        close();
    }
}
