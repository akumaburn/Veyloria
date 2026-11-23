package com.eonmux.chatllm.comfyui;

import com.eonmux.chatllm.config.AppConfig;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class ComfyUIWorkflowBuilder {

    private final AppConfig config;

    @Inject
    public ComfyUIWorkflowBuilder(AppConfig config) {
        this.config = config;
    }

    // Default constructor for CDI proxying.
    protected ComfyUIWorkflowBuilder() {
        this.config = null;
    }

    /**
     * Build a text-to-image workflow with the given parameters.
     */
    public JsonObject buildTxt2ImgWorkflow(String positivePrompt, String negativePrompt,
                                            int width, int height, long seed,
                                            String checkpoint, int steps) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            checkpoint = config.getComfyuiDefaultCheckpoint();
        }
        if (steps <= 0) steps = config.getComfyuiDefaultSteps();
        if (seed <= 0) seed = (long) (Math.random() * Long.MAX_VALUE);
        if (negativePrompt == null) negativePrompt = "low quality, blurry, deformed";

        String sampler = config.getComfyuiDefaultSampler();

        // Standard ComfyUI API workflow for txt2img
        JsonObject workflow = new JsonObject();

        // Node 1: Checkpoint loader
        workflow.put("1", new JsonObject()
            .put("class_type", "CheckpointLoaderSimple")
            .put("inputs", new JsonObject()
                .put("ckpt_name", checkpoint)));

        // Node 2: CLIP Text Encode (positive)
        workflow.put("2", new JsonObject()
            .put("class_type", "CLIPTextEncode")
            .put("inputs", new JsonObject()
                .put("text", positivePrompt)
                .put("clip", new io.vertx.core.json.JsonArray().add("1").add(1))));

        // Node 3: CLIP Text Encode (negative)
        workflow.put("3", new JsonObject()
            .put("class_type", "CLIPTextEncode")
            .put("inputs", new JsonObject()
                .put("text", negativePrompt)
                .put("clip", new io.vertx.core.json.JsonArray().add("1").add(1))));

        // Node 4: Empty latent image
        workflow.put("4", new JsonObject()
            .put("class_type", "EmptyLatentImage")
            .put("inputs", new JsonObject()
                .put("width", width)
                .put("height", height)
                .put("batch_size", 1)));

        // Node 5: KSampler
        workflow.put("5", new JsonObject()
            .put("class_type", "KSampler")
            .put("inputs", new JsonObject()
                .put("model", new io.vertx.core.json.JsonArray().add("1").add(0))
                .put("positive", new io.vertx.core.json.JsonArray().add("2").add(0))
                .put("negative", new io.vertx.core.json.JsonArray().add("3").add(0))
                .put("latent_image", new io.vertx.core.json.JsonArray().add("4").add(0))
                .put("seed", seed)
                .put("steps", steps)
                .put("cfg", 7.0)
                .put("sampler_name", sampler)
                .put("scheduler", "normal")
                .put("denoise", 1.0)));

        // Node 6: VAE Decode
        workflow.put("6", new JsonObject()
            .put("class_type", "VAEDecode")
            .put("inputs", new JsonObject()
                .put("samples", new io.vertx.core.json.JsonArray().add("5").add(0))
                .put("vae", new io.vertx.core.json.JsonArray().add("1").add(2))));

        // Node 7: Save Image
        workflow.put("7", new JsonObject()
            .put("class_type", "SaveImage")
            .put("inputs", new JsonObject()
                .put("images", new io.vertx.core.json.JsonArray().add("6").add(0))
                .put("filename_prefix", "chatllm")));

        return workflow;
    }

    /**
     * Build a workflow from a stored template with parameter overrides.
     */
    public JsonObject buildFromTemplate(JsonObject templateWorkflow, JsonObject overrides) {
        JsonObject workflow = templateWorkflow.copy();
        
        if (overrides == null) return workflow;

        // Apply overrides to matching nodes
        for (String nodeId : workflow.fieldNames()) {
            JsonObject node = workflow.getJsonObject(nodeId);
            if (node == null) continue;
            
            String classType = node.getString("class_type", "");
            JsonObject inputs = node.getJsonObject("inputs");
            if (inputs == null) continue;

            // Override positive prompt
            if ("CLIPTextEncode".equals(classType) && overrides.containsKey("positive_prompt")) {
                if (inputs.containsKey("text") && !inputs.getString("text", "").contains("negative")) {
                    inputs.put("text", overrides.getString("positive_prompt"));
                }
            }

            // Override seed
            if ("KSampler".equals(classType) && overrides.containsKey("seed")) {
                inputs.put("seed", overrides.getLong("seed"));
            }

            // Override dimensions
            if ("EmptyLatentImage".equals(classType)) {
                if (overrides.containsKey("width")) inputs.put("width", overrides.getInteger("width"));
                if (overrides.containsKey("height")) inputs.put("height", overrides.getInteger("height"));
            }
        }

        return workflow;
    }
}
