package com.eonmux.chatllm.comfyui;

import com.eonmux.chatllm.config.AppConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Slf4j
public class PromptEnhancer {

    private final Vertx vertx;
    private final Pool mysqlPool;
    private final AppConfig config;

    @Inject
    public PromptEnhancer(Vertx vertx, Pool mysqlPool, AppConfig config) {
        this.vertx = vertx;
        this.mysqlPool = mysqlPool;
        this.config = config;
    }

    /**
     * Enhance a narrative description into an image generation prompt without
     * world-state tags.
     */
    public Uni<ImagePrompt> enhance(String narrativeContext, String characterId, String triggerType) {
        return enhance(narrativeContext, characterId, triggerType, null);
    }

    /**
     * Enhance a narrative description into an image generation prompt.
     * Appends world-state tags (weather, time of day, mood, location) from the
     * conversation's world_state row when supplied, so generated backdrops
     * match the authoritative scene.
     */
    public Uni<ImagePrompt> enhance(String narrativeContext, String characterId, String triggerType, JsonObject worldState) {
        // Get character visual references if characterId is provided
        Uni<List<VisualRef>> refsUni = characterId != null
            ? getCharacterVisualRefs(characterId)
            : Uni.createFrom().item(new ArrayList<>());

        return refsUni.map(refs -> {
            StringBuilder positive = new StringBuilder();
            StringBuilder negative = new StringBuilder();

            // Add trigger-type-specific quality tags
            switch (triggerType != null ? triggerType : "scene_change") {
                case "scene_change":
                    positive.append("detailed landscape, environment art, atmospheric lighting, ");
                    break;
                case "character_portrait":
                    positive.append("character portrait, detailed face, expressive eyes, ");
                    break;
                case "action_shot":
                    positive.append("dynamic action scene, motion blur, dramatic lighting, ");
                    break;
                case "item":
                    positive.append("detailed object, close-up, studio lighting, ");
                    break;
                case "map":
                    positive.append("top-down map view, fantasy cartography, detailed, ");
                    break;
            }

            // Add authoritative world-state tags so the backdrop matches the scene.
            if (worldState != null) {
                List<String> tags = new ArrayList<>();
                for (String key : new String[]{"weather", "timeOfDay", "mood", "currentLocation"}) {
                    String value = worldState.getString(key);
                    if (value != null && !value.isBlank()) tags.add(value.trim());
                }
                if (!tags.isEmpty()) positive.append(String.join(", ", tags)).append(", ");
            }

            // Add narrative context as main prompt
            positive.append(narrativeToPrompt(narrativeContext));

            // Add character visual references
            for (VisualRef ref : refs) {
                if (ref.promptFragment != null) {
                    positive.append(", ").append(ref.promptFragment);
                }
                if (ref.negativeFragment != null) {
                    negative.append(ref.negativeFragment).append(", ");
                }
            }

            // Add quality tags
            positive.append(", masterpiece, best quality, highly detailed");
            negative.append("low quality, blurry, deformed, disfigured, bad anatomy, watermark, text");

            return new ImagePrompt(positive.toString(), negative.toString(), refs);
        });
    }

    /**
     * Convert narrative text to SD-style prompt keywords.
     */
    private String narrativeToPrompt(String narrative) {
        if (narrative == null || narrative.isEmpty()) return "";

        // Extract key descriptive elements
        String prompt = narrative
            .replaceAll("\\[IMAGE:\\s*(.+?)\\]", "$1") // Extract IMAGE tag content
            .replaceAll("[.!?]+", ",")           // Sentences to comma-separated
            .replaceAll("\\s+", " ")             // Normalize whitespace
            .replaceAll("(he|she|they|it)\\s+(is|are|was|were)\\s+", "") // Remove pronouns
            .replaceAll("(the|a|an)\\s+", "")    // Remove articles
            .trim();

        // Truncate to reasonable length
        if (prompt.length() > 300) {
            prompt = prompt.substring(0, 300);
            int lastComma = prompt.lastIndexOf(",");
            if (lastComma > 200) prompt = prompt.substring(0, lastComma);
        }

        return prompt;
    }

    /**
     * Get visual references for a character.
     */
    private Uni<List<VisualRef>> getCharacterVisualRefs(String characterId) {
        String query = "SELECT * FROM character_visual_ref WHERE characterId = ? AND isActive = TRUE ORDER BY weight DESC";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(characterId))
            .map(rows -> {
                List<VisualRef> refs = new ArrayList<>();
                for (var row : rows) {
                    VisualRef ref = new VisualRef();
                    ref.referenceType = row.getString("referenceType");
                    ref.referencePath = row.getString("referencePath");
                    ref.promptFragment = row.getString("promptFragment");
                    ref.negativeFragment = row.getString("negativeFragment");
                    ref.weight = row.getDouble("weight");
                    refs.add(ref);
                }
                return refs;
            })
            .onFailure().recoverWithItem(new ArrayList<>());
    }

    public static class ImagePrompt {
        public final String positive;
        public final String negative;
        public final List<VisualRef> references;

        public ImagePrompt(String positive, String negative, List<VisualRef> references) {
            this.positive = positive;
            this.negative = negative;
            this.references = references;
        }
    }

    public static class VisualRef {
        public String referenceType;
        public String referencePath;
        public String promptFragment;
        public String negativeFragment;
        public Double weight;
    }
}
