package com.eonmux.chatllm.comfyui;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph-transformation tests for template-based workflow building: prompt
 * node resolution through the KSampler wiring, dimension/sampler overrides,
 * and LoRA stage insertion/removal with reference rewiring.
 */
class ComfyUIWorkflowBuilderTest {

    private final ComfyUIWorkflowBuilder builder = new ComfyUIWorkflowBuilder();

    /** Standard phase-1 template shape (checkpoint, two encodes, latent, sampler, decode, save). */
    private static JsonObject standardTemplate() {
        return new JsonObject("""
            {
              "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "placeholder.safetensors"}},
              "2": {"class_type": "CLIPTextEncode", "inputs": {"text": "positive default", "clip": ["1", 1]}},
              "3": {"class_type": "CLIPTextEncode", "inputs": {"text": "negative default", "clip": ["1", 1]}},
              "4": {"class_type": "EmptyLatentImage", "inputs": {"width": 512, "height": 512, "batch_size": 1}},
              "5": {"class_type": "KSampler", "inputs": {
                  "model": ["1", 0], "positive": ["2", 0], "negative": ["3", 0], "latent_image": ["4", 0],
                  "seed": 0, "steps": 20, "cfg": 7.0, "sampler_name": "euler_a", "scheduler": "normal", "denoise": 1.0}},
              "6": {"class_type": "VAEDecode", "inputs": {"samples": ["5", 0], "vae": ["1", 2]}},
              "7": {"class_type": "SaveImage", "inputs": {"images": ["6", 0], "filename_prefix": "chatllm"}}
            }
            """);
    }

    private static JsonObject loraTemplate() {
        JsonObject workflow = standardTemplate();
        workflow.put("8", new JsonObject()
            .put("class_type", "LoraLoader")
            .put("inputs", new JsonObject()
                .put("model", new io.vertx.core.json.JsonArray().add("1").add(0))
                .put("clip", new io.vertx.core.json.JsonArray().add("1").add(1))
                .put("vae", new io.vertx.core.json.JsonArray().add("1").add(2))
                .put("lora_name", "old.safetensors")
                .put("strength_model", 1.0)
                .put("strength_clip", 1.0)));
        // Rewire the checkpoint consumers to the LoRA stage, as a real graph would.
        workflow.getJsonObject("5").getJsonObject("inputs").put("model", new io.vertx.core.json.JsonArray().add("8").add(0));
        workflow.getJsonObject("2").getJsonObject("inputs").put("clip", new io.vertx.core.json.JsonArray().add("8").add(1));
        workflow.getJsonObject("3").getJsonObject("inputs").put("clip", new io.vertx.core.json.JsonArray().add("8").add(1));
        workflow.getJsonObject("6").getJsonObject("inputs").put("vae", new io.vertx.core.json.JsonArray().add("8").add(2));
        return workflow;
    }

    @Test
    void overridesResolvePromptsThroughSamplerWiring() {
        JsonObject workflow = builder.buildFromTemplate(standardTemplate(), new JsonObject()
            .put("positive_prompt", "a new positive")
            .put("negative_prompt", "a new negative")
            .put("width", 832)
            .put("height", 1216)
            .put("seed", 42L)
            .put("steps", 15)
            .put("cfg", 6.5)
            .put("checkpoint", "world.safetensors"));

        assertEquals("a new positive", workflow.getJsonObject("2").getJsonObject("inputs").getString("text"));
        assertEquals("a new negative", workflow.getJsonObject("3").getJsonObject("inputs").getString("text"));
        assertEquals(832, workflow.getJsonObject("4").getJsonObject("inputs").getInteger("width"));
        assertEquals(1216, workflow.getJsonObject("4").getJsonObject("inputs").getInteger("height"));
        assertEquals(42L, workflow.getJsonObject("5").getJsonObject("inputs").getLong("seed"));
        assertEquals(15, workflow.getJsonObject("5").getJsonObject("inputs").getInteger("steps"));
        assertEquals(6.5, workflow.getJsonObject("5").getJsonObject("inputs").getDouble("cfg"));
        assertEquals("world.safetensors", workflow.getJsonObject("1").getJsonObject("inputs").getString("ckpt_name"));
    }

    @Test
    void absentPromptsLeaveTemplateTextIntact() {
        JsonObject workflow = builder.buildFromTemplate(standardTemplate(), new JsonObject()
            .put("width", 768)
            .put("height", 768));

        assertEquals("positive default", workflow.getJsonObject("2").getJsonObject("inputs").getString("text"));
        assertEquals("negative default", workflow.getJsonObject("3").getJsonObject("inputs").getString("text"));
    }

    @Test
    void loraStageInsertedWhenNameSupplied() {
        JsonObject template = standardTemplate();
        JsonObject workflow = builder.buildFromTemplate(template, new JsonObject()
            .put("lora", "style.safetensors"));

        JsonObject lora = workflow.getJsonObject("lora");
        assertNotNull(lora);
        assertEquals("LoraLoader", lora.getString("class_type"));
        assertEquals("style.safetensors", lora.getJsonObject("inputs").getString("lora_name"));
        // The sampler now consumes the LoRA output, not the checkpoint.
        var model = workflow.getJsonObject("5").getJsonObject("inputs").getJsonArray("model");
        assertEquals("lora", model.getString(0));
        // The template passed in is not mutated.
        assertNull(template.getJsonObject("lora"));
        var originalModel = template.getJsonObject("5").getJsonObject("inputs").getJsonArray("model");
        assertEquals("1", originalModel.getString(0));
    }

    @Test
    void loraStageRemovedWhenNameEmpty() {
        JsonObject workflow = builder.buildFromTemplate(loraTemplate(), new JsonObject()
            .put("lora", ""));

        assertNull(workflow.getJsonObject("8"));
        // References rewired back to the checkpoint.
        var model = workflow.getJsonObject("5").getJsonObject("inputs").getJsonArray("model");
        assertEquals("1", model.getString(0));
        var clip = workflow.getJsonObject("2").getJsonObject("inputs").getJsonArray("clip");
        assertEquals("1", clip.getString(0));
        var vae = workflow.getJsonObject("6").getJsonObject("inputs").getJsonArray("vae");
        assertEquals("1", vae.getString(0));
    }

    @Test
    void loraNameReplacedWhenStageAlreadyPresent() {
        JsonObject workflow = builder.buildFromTemplate(loraTemplate(), new JsonObject()
            .put("lora", "other.safetensors"));

        assertEquals("other.safetensors", workflow.getJsonObject("8").getJsonObject("inputs").getString("lora_name"));
        assertFalse(workflow.fieldNames().contains("lora"));
    }

    @Test
    void nullInputsAreSafe() {
        assertTrue(builder.buildFromTemplate(null, new JsonObject()).fieldNames().isEmpty());
        JsonObject unchanged = builder.buildFromTemplate(standardTemplate(), null);
        assertEquals(7, unchanged.fieldNames().size());
    }

    /** Minimal H3 Ref2VA shaped template in the built-in contract form:
     *  ref_images is an ordered map ref_image_N -> [node, slot]. */
    private static JsonObject ref2VATemplate() {
        return new JsonObject("""
            {
              "12": {"class_type": "CLIPLoader", "inputs": {"clip_name": "clip.safetensors"}},
              "13": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["12", 0], "text": "template prompt"}},
              "100": {"class_type": "LoadImage", "inputs": {"image": "ref_image_0.png"}},
              "101": {"class_type": "LoadImage", "inputs": {"image": "ref_image_1.png"}},
              "102": {"class_type": "LoadImage", "inputs": {"image": "ref_image_2.png"}},
              "14": {"class_type": "MiniMaxH3ReferenceToVideo", "inputs": {
                  "clip": ["12", 0], "prompt": "template prompt", "width": 1024, "height": 1024,
                  "length": 5, "ref_image_size": "max",
                  "ref_images": {"ref_image_0": ["100", 0], "ref_image_1": ["101", 0], "ref_image_2": ["102", 0]},
                  "vae": ["11", 0]}},
              "18": {"class_type": "RandomNoise", "inputs": {"noise_seed": 1}},
              "22": {"class_type": "SaveImage", "inputs": {"images": ["21", 0], "filename_prefix": "x"}}
            }
            """);
    }

    /** Same graph with the plain [node, slot] list form some submitted graphs use. */
    private static JsonObject ref2VAListFormTemplate() {
        return new JsonObject("""
            {
              "13": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["12", 0], "text": "template prompt"}},
              "100": {"class_type": "LoadImage", "inputs": {"image": "ref_image_0.png"}},
              "101": {"class_type": "LoadImage", "inputs": {"image": "ref_image_1.png"}},
              "14": {"class_type": "MiniMaxH3ReferenceToVideo", "inputs": {
                  "clip": ["12", 0], "prompt": "template prompt", "width": 1024, "height": 1024,
                  "length": 5, "ref_image_size": "max", "ref_images": [["100", 0], ["101", 0]],
                  "vae": ["11", 0]}},
              "18": {"class_type": "RandomNoise", "inputs": {"noise_seed": 1}}
            }
            """);
    }

    @Test
    void ref2VASlotsFilledAndRefsTrimmed() {
        JsonObject template = ref2VATemplate();
        JsonObject workflow = builder.buildRef2VAWorkflow(template, "final prompt", 777L, 768, 768,
            List.of("uploaded_a.png", "uploaded_b.png"));

        // Prompt text synced across encoder and model nodes; size and seed set.
        assertEquals("final prompt", workflow.getJsonObject("13").getJsonObject("inputs").getString("text"));
        JsonObject model = workflow.getJsonObject("14").getJsonObject("inputs");
        assertEquals("final prompt", model.getString("prompt"));
        assertEquals(768, model.getInteger("width"));
        assertEquals(768, model.getInteger("height"));
        assertEquals(777L, workflow.getJsonObject("18").getJsonObject("inputs").getLong("noise_seed"));

        // Kept refs point at the uploaded names; the unused tail node is gone.
        assertEquals("uploaded_a.png", workflow.getJsonObject("100").getJsonObject("inputs").getString("image"));
        assertEquals("uploaded_b.png", workflow.getJsonObject("101").getJsonObject("inputs").getString("image"));
        assertNull(workflow.getJsonObject("102"));
        // The map form is normalized to the [node, slot] list the node consumes.
        var refs = model.getJsonArray("ref_images");
        assertEquals(2, refs.size());
        assertEquals("100", refs.getJsonArray(0).getString(0));
        assertEquals("101", refs.getJsonArray(1).getString(0));

        // The template passed in is not mutated.
        assertEquals(3, template.getJsonObject("14").getJsonObject("inputs").getJsonObject("ref_images").size());
    }

    @Test
    void ref2VAListFormRefsAlsoTrimmed() {
        JsonObject template = ref2VAListFormTemplate();
        JsonObject workflow = builder.buildRef2VAWorkflow(template, "p", 1L, 768, 768, List.of("only.png"));

        assertEquals("only.png", workflow.getJsonObject("100").getJsonObject("inputs").getString("image"));
        assertNull(workflow.getJsonObject("101"));
        var refs = workflow.getJsonObject("14").getJsonObject("inputs").getJsonArray("ref_images");
        assertEquals(1, refs.size());
        assertEquals("100", refs.getJsonArray(0).getString(0));
    }

    @Test
    void promptSlotsFilledFromWorldState() {
        JsonObject world = new JsonObject()
            .put("weather", "light rain").put("timeOfDay", "dusk").put("mood", "melancholy");
        String text = ComfyUIWorkflowBuilder.fillPromptSlots(
            "A {biome} landscape. Weather: {weather}. Time of day: {time_of_day}. Mood: {mood}.", world);
        assertEquals("A temperate landscape. Weather: light rain. Time of day: dusk. Mood: melancholy.", text);
        // Null world state leaves the placeholders untouched.
        assertEquals("A {biome} landscape.", ComfyUIWorkflowBuilder.fillPromptSlots("A {biome} landscape.", null));
    }

    @Test
    void seedExtractionCoversBothNodeShapes() {
        // extractSeed reads the submitted workflow graph (JsonObject keyed by
        // node ID). /history entries do not record the graph, so the seed is
        // captured at submit time, not from history.
        assertEquals(42L, ComfyUIQueueManager.extractSeed(new JsonObject(
            """
            {"5": {"class_type": "KSampler", "inputs": {"seed": 42}}}
            """)));
        assertEquals(99L, ComfyUIQueueManager.extractSeed(new JsonObject(
            """
            {"18": {"class_type": "RandomNoise", "inputs": {"noise_seed": 99}}}
            """)));
        assertEquals(0L, ComfyUIQueueManager.extractSeed(null));
        // A graph without a recognizable seed node degrades to 0, never throws.
        assertEquals(0L, ComfyUIQueueManager.extractSeed(new JsonObject()));
        assertEquals(0L, ComfyUIQueueManager.extractSeed(new JsonObject()
            .put("10", new JsonObject().put("class_type", "UNETLoader"))));
    }
}
