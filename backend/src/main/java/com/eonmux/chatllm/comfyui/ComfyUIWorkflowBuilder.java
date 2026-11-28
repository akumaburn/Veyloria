package com.eonmux.chatllm.comfyui;

import com.eonmux.chatllm.config.AppConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

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
     *
     * Supported overrides: positive_prompt, negative_prompt, checkpoint,
     * seed, steps, cfg, sampler_name, width, height, and lora (a LoRA file
     * name; empty removes the LoRA stage). The positive and negative text
     * nodes are resolved from the KSampler wiring, never guessed by content.
     * A LoraLoader is inserted between the checkpoint and its consumers when
     * a name is supplied, and removed (references rewired to the checkpoint)
     * when absent, so templates are LoRA-agnostic.
     */
    public JsonObject buildFromTemplate(JsonObject templateWorkflow, JsonObject overrides) {
        if (templateWorkflow == null) return new JsonObject();
        // Deep copy: JsonObject.copy() shares nested values, which would let
        // graph mutation corrupt the stored template.
        JsonObject workflow = new JsonObject(templateWorkflow.encode());

        if (overrides == null) return workflow;

        String checkpointId = findNodeIdByClass(workflow, "CheckpointLoaderSimple");
        if (checkpointId != null && overrides.containsKey("checkpoint")) {
            workflow.getJsonObject(checkpointId).getJsonObject("inputs").put("ckpt_name", overrides.getString("checkpoint"));
        }

        String positiveId = null;
        String negativeId = null;

        for (String nodeId : nodeIds(workflow)) {
            JsonObject node = workflow.getJsonObject(nodeId);
            if (node == null) continue;
            String classType = node.getString("class_type", "");
            JsonObject inputs = node.getJsonObject("inputs");
            if (inputs == null) continue;

            if ("KSampler".equals(classType) || "KSamplerAdvanced".equals(classType)) {
                JsonArray pos = inputs.getJsonArray("positive", new JsonArray());
                JsonArray neg = inputs.getJsonArray("negative", new JsonArray());
                if (pos.size() > 0) positiveId = pos.getString(0);
                if (neg.size() > 0) negativeId = neg.getString(0);
                if (overrides.containsKey("seed")) inputs.put("seed", overrides.getLong("seed"));
                if (overrides.containsKey("steps")) inputs.put("steps", overrides.getInteger("steps"));
                if (overrides.containsKey("cfg")) inputs.put("cfg", overrides.getValue("cfg"));
                if (overrides.containsKey("sampler_name")) inputs.put("sampler_name", overrides.getString("sampler_name"));
            }

            if ("EmptyLatentImage".equals(classType)) {
                if (overrides.containsKey("width")) inputs.put("width", overrides.getInteger("width"));
                if (overrides.containsKey("height")) inputs.put("height", overrides.getInteger("height"));
            }
        }

        // Null values are skipped so a template's own text survives when the
        // caller supplied no prompt for that node (put(key, null) would delete it).
        if (positiveId != null && overrides.getString("positive_prompt") != null) {
            setNodeText(workflow, positiveId, overrides.getString("positive_prompt"));
        }
        if (negativeId != null && overrides.getString("negative_prompt") != null) {
            setNodeText(workflow, negativeId, overrides.getString("negative_prompt"));
        }

        applyLoraOverride(workflow, checkpointId, overrides.getString("lora", ""));

        return workflow;
    }

    /**
     * Fill the MiniMax H3 Ref2VA graph slots: the prompt text (encoder and
     * model nodes kept in sync), output size, noise seed, and reference
     * images. Unused LoadImage nodes and their trailing entries in the
     * model node's ref_images list are removed when fewer references are
     * supplied than the template ships with.
     */
    public JsonObject buildRef2VAWorkflow(JsonObject template, String prompt, long seed,
                                          int width, int height, List<String> refImageNames) {
        if (template == null) return new JsonObject();
        JsonObject workflow = new JsonObject(template.encode());

        for (String nodeId : nodeIds(workflow)) {
            JsonObject node = workflow.getJsonObject(nodeId);
            if (node == null) continue;
            String classType = node.getString("class_type", "");
            JsonObject inputs = node.getJsonObject("inputs");
            if (inputs == null) continue;
            if ("CLIPTextEncode".equals(classType) && inputs.containsKey("text")) {
                inputs.put("text", prompt);
            }
            if ("MiniMaxH3ReferenceToVideo".equals(classType)) {
                inputs.put("prompt", prompt);
                inputs.put("width", width);
                inputs.put("height", height);
            }
            if ("RandomNoise".equals(classType) && seed > 0) {
                inputs.put("noise_seed", seed);
            }
        }

        if (refImageNames != null) applyRefImages(workflow, refImageNames);
        return workflow;
    }

    /**
     * Point the kept LoadImage nodes at the uploaded files and trim the
     * template's reference list to the supplied count, deleting orphaned
     * LoadImage nodes.
     */
    private static void applyRefImages(JsonObject workflow, List<String> refImageNames) {
        String modelId = findNodeIdByClass(workflow, "MiniMaxH3ReferenceToVideo");
        if (modelId == null) return;
        JsonObject modelInputs = workflow.getJsonObject(modelId).getJsonObject("inputs");

        // The built-in H3 template stores ref_images as an ordered map
        // (ref_image_0..N -> [node, slot]); user-submitted graphs may use the
        // plain [node, slot] list. Normalize to the list form the model node
        // consumes, then trim.
        Object raw = modelInputs.getValue("ref_images");
        if (raw instanceof JsonObject map) {
            JsonArray list = new JsonArray();
            for (String key : map.fieldNames()) {
                JsonArray pair = map.getJsonArray(key);
                if (pair != null) list.add(pair);
            }
            modelInputs.put("ref_images", list);
            raw = list;
        }
        if (!(raw instanceof JsonArray refs)) return;

        int keep = Math.min(refImageNames.size(), refs.size());
        for (int i = 0; i < keep; i++) {
            JsonArray ref = refs.getJsonArray(i);
            String nodeId = ref.getString(0);
            JsonObject node = workflow.getJsonObject(nodeId);
            if (node != null && "LoadImage".equals(node.getString("class_type", ""))) {
                node.getJsonObject("inputs").put("image", refImageNames.get(i));
            }
        }
        while (refs.size() > keep) {
            JsonArray dropped = refs.getJsonArray(refs.size() - 1);
            String nodeId = dropped.getString(0);
            refs.remove(refs.size() - 1);
            JsonObject node = workflow.getJsonObject(nodeId);
            if (node != null && "LoadImage".equals(node.getString("class_type", ""))) {
                workflow.remove(nodeId);
            }
        }
    }

    /**
     * Replace {biome}, {weather}, {time_of_day}, and {mood} placeholders in a
     * template prompt with the conversation's world-state values. Missing
     * values fall back to neutral defaults so the prompt stays grammatical.
     */
    public static String fillPromptSlots(String text, JsonObject worldState) {
        if (text == null) return "";
        if (worldState == null) return text;
        return text
            .replace("{biome}", slotValue(worldState, "biome", "temperate"))
            .replace("{weather}", slotValue(worldState, "weather", "clear"))
            .replace("{time_of_day}", slotValue(worldState, "timeOfDay", "daylight"))
            .replace("{mood}", slotValue(worldState, "mood", "calm"));
    }

    private static String slotValue(JsonObject worldState, String key, String fallback) {
        String value = worldState.getString(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /**
     * Top-level keys that are node objects. A stored template may carry
     * non-node metadata entries (strings, numbers); those are skipped so
     * node iteration never casts a metadata value to a JsonObject.
     */
    private static List<String> nodeIds(JsonObject workflow) {
        List<String> ids = new ArrayList<>();
        for (String id : workflow.fieldNames()) {
            if (workflow.getValue(id) instanceof JsonObject) ids.add(id);
        }
        return ids;
    }

    private static String findNodeIdByClass(JsonObject workflow, String classType) {
        for (String nodeId : workflow.fieldNames()) {
            if (workflow.getValue(nodeId) instanceof JsonObject node
                    && classType.equals(node.getString("class_type", ""))) return nodeId;
        }
        return null;
    }

    private static void setNodeText(JsonObject workflow, String nodeId, String text) {
        JsonObject node = workflow.getJsonObject(nodeId);
        if (node == null) return;
        JsonObject inputs = node.getJsonObject("inputs");
        if (inputs == null) return;
        inputs.put("text", text);
    }

    /**
     * Insert or remove the single LoRA stage so the built graph matches the
     * override. Slot k of the checkpoint (0 model, 1 clip, 2 vae) is mirrored
     * by slot k of the LoRA loader.
     */
    private static void applyLoraOverride(JsonObject workflow, String checkpointId, String loraName) {
        if (checkpointId == null) return;
        String loraId = findNodeIdByClass(workflow, "LoraLoader");

        if (loraName != null && !loraName.isEmpty()) {
            if (loraId == null) {
                loraId = "lora";
                workflow.put(loraId, new JsonObject()
                    .put("class_type", "LoraLoader")
                    .put("inputs", new JsonObject()
                        .put("model", new JsonArray().add(checkpointId).add(0))
                        .put("clip", new JsonArray().add(checkpointId).add(1))
                        .put("vae", new JsonArray().add(checkpointId).add(2))
                        .put("lora_name", loraName)
                        .put("strength_model", 1.0)
                        .put("strength_clip", 1.0)));
                rerouteReferences(workflow, checkpointId, loraId);
            } else {
                workflow.getJsonObject(loraId).getJsonObject("inputs").put("lora_name", loraName);
            }
        } else if (loraId != null) {
            rerouteReferences(workflow, loraId, checkpointId);
            workflow.remove(loraId);
        }
    }

    /** Point every [fromId, k] wire reference at [toId, k]; k is preserved. */
    private static void rerouteReferences(JsonObject workflow, String fromId, String toId) {
        for (String nodeId : nodeIds(workflow)) {
            if (nodeId.equals(fromId) || nodeId.equals(toId)) continue;
            JsonObject node = workflow.getJsonObject(nodeId);
            if (node == null) continue;
            JsonObject inputs = node.getJsonObject("inputs");
            if (inputs == null) continue;
            for (String key : new ArrayList<>(inputs.fieldNames())) {
                Object value = inputs.getValue(key);
                if (!(value instanceof JsonArray)) continue; // getJsonArray throws on strings
                JsonArray ref = (JsonArray) value;
                if (ref.size() < 2) continue;
                String refNodeId = ref.getString(0);
                if (refNodeId == null || !refNodeId.equals(fromId)) continue;
                ref.set(0, toId);
            }
        }
    }
}
