package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * Character entity matching the GraphQL schema and frontend expectations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Character {
    private UUID id;
    private String name;
    private String description;
    private String systemPrompt;
    private String greeting; // Canonical greeting field (consolidated from greeting/initialPrompt/firstMessage)
    private String appearance;
    private String personality;

    // Physical profile generated with the character. Each value is optional so
    // older characters keep working; the card hides a fact it does not have.
    private String gender;
    private String pronouns;
    private Integer ageYears;
    private String height;
    private String build;
    private String hair;
    private String eyes;

    // Base attributes on a 3-18 scale, 10 is average. They describe the body
    // and mind the character starts with; the conversation sheet tracks what
    // changes during play.
    private Integer strength;
    private Integer dexterity;
    private Integer constitution;
    private Integer intelligence;
    private Integer wisdom;
    private Integer charisma;

    private String likes;
    private String dislikes;
    private String backstory;
    private String goals;
    // Note: initialPrompt removed - use greeting instead (getter/setter provided for backwards compatibility)
    private String examples;
    private String avatar;
    private Boolean isDefault;
    @Builder.Default
    private Boolean isPublic = false; // New field with default
    @Builder.Default
    private Boolean isFavorite = false; // User-specific favorite status
    private UUID ownerId;
    
    // LLM parameters (new fields)
    @Builder.Default
    private Double temperature = 0.7;
    @Builder.Default
    private Integer maxTokens = 2000;
    @Builder.Default
    private Double topP = 0.9;
    @Builder.Default
    private Integer topK = 40;
    @Builder.Default
    private Double repeatPenalty = 1.0;
    
    // Statistics (new fields)
    @Builder.Default
    private Double rating = 0.0;
    @Builder.Default
    private Integer usageCount = 0;
    @Builder.Default
    private String category = "general";
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /**
     * Starting feelings for a pair that has no recorded affinity yet. The character
     * form configures these; both default to neutral (0). Once the user records a
     * reaction in either direction, the saved points replace the starting value.
     */
    @Builder.Default
    private Integer initialAffinityToUser = 0;
    @Builder.Default
    private Integer initialAffinityFromUser = 0;
    
    // Timestamps
    private String createdAt;
    private String updatedAt;
    private String lastModified;
    
    @org.eclipse.microprofile.graphql.Ignore
    private io.vertx.core.json.JsonObject metadata;
    private Boolean publicField; // Alternative name for 'public'
    private String ownerName;
    private Integer conversationCount;
    
    // Persisted media fields (photoAlbum resolved via CharacterResolver @Source method)
    @Builder.Default
    @org.eclipse.microprofile.graphql.Ignore
    private List<String> photoAlbum = new ArrayList<>();
    private String chatBackgroundImage;

    // Relationships (for GraphQL field resolution)
    private transient User owner;
    @org.eclipse.microprofile.graphql.Ignore
    private transient User creator; // Alias for owner (resolved via CharacterResolver @Source method)
    
    // Constants
    public static final UUID DEFAULT_ASSISTANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    
    /**
     * Create default assistant character.
     */
    public static Character createDefaultAssistant() {
        return Character.builder()
            .id(DEFAULT_ASSISTANT_ID)
            .name("Assistant")
            .description("Default AI assistant")
            .systemPrompt("You are a helpful AI assistant.")
            .greeting("Hello! How can I help you today?")
            .isDefault(true)
            .isPublic(true)
            .temperature(0.7)
            .maxTokens(2000)
            .topP(0.9)
            .topK(40)
            .repeatPenalty(1.0)
            .rating(5.0)
            .usageCount(0)
            .createdAt(Time4JHelper.nowUTCString())
            .updatedAt(Time4JHelper.nowUTCString())
            .build();
    }
    
    /**
     * Check if this is the default assistant.
     */
    public boolean isDefaultAssistant() {
        return DEFAULT_ASSISTANT_ID.equals(id) || Boolean.TRUE.equals(isDefault);
    }
    
    /**
     * Get greeting message for new conversation.
     * Returns the greeting field or a default message if not set.
     */
    public String getEffectiveGreeting() {
        if (greeting != null && !greeting.isEmpty()) {
            return greeting;
        }
        return "Hello! I'm " + (name != null ? name : "your AI assistant") + ". How can I help you today?";
    }

    /**
     * The sentence of the personality that governs how this character speaks,
     * or null when the personality does not describe speech.
     */
    public String getSpeechTrait() {
        if (personality == null || personality.isBlank()) return null;
        for (String sentence : personality.split("(?<=[.!?])\\s+")) {
            String lower = sentence.toLowerCase();
            if (lower.contains("speech") || lower.contains("speaks") || lower.contains("voice")
                    || lower.contains("talks") || lower.contains("utter")) {
                return sentence.strip();
            }
        }
        return null;
    }

    /**
     * A compact voice contract for the end of a roleplay system prompt.
     *
     * The personality and greeting sit near the top of a long prompt, where the
     * shared scene and format rules that follow outweigh them. Repeating the
     * speech trait and one real line here puts this person, rather than a
     * template, closest to the point of generation.
     */
    public String getVoiceAnchor() {
        StringBuilder anchor = new StringBuilder();
        String trait = getSpeechTrait();
        if (trait != null && !trait.isBlank()) {
            anchor.append(trait.strip());
        }
        if (greeting != null && !greeting.isBlank()) {
            if (anchor.length() > 0) anchor.append('\n');
            anchor.append("A line this character has said, in their own voice. ")
                    .append("Match its rhythm and vocabulary; do not reuse the line itself:\n\"")
                    .append(greeting.strip()).append('"');
        }
        return anchor.toString();
    }

    /**
     * Get full system prompt including all character details.
     * Creates a comprehensive roleplay instruction set for the AI to embody this character.
     */
    public String getFullSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        String charName = name != null ? name : "an AI character";

        // Primary system prompt or identity statement
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append(systemPrompt);
        } else {
            prompt.append("You are ").append(charName).append(".");
        }

        // Add character details if available
        boolean hasDetails = false;

        if (description != null && !description.isEmpty()) {
            prompt.append("\n\n=== WHO YOU ARE ===\n").append(description);
            hasDetails = true;
        }

        if (personality != null && !personality.isEmpty()) {
            prompt.append("\n\n=== YOUR PERSONALITY ===\n").append(personality);
            hasDetails = true;
        }

        if (appearance != null && !appearance.isEmpty()) {
            prompt.append("\n\n=== YOUR APPEARANCE ===\n").append(appearance);
            hasDetails = true;
        }

        StringBuilder profile = new StringBuilder();
        if (gender != null && !gender.isBlank()) profile.append("Gender: ").append(gender).append('\n');
        if (pronouns != null && !pronouns.isBlank()) profile.append("Pronouns: ").append(pronouns).append('\n');
        if (ageYears != null) profile.append("Age: ").append(ageYears).append('\n');
        if (height != null && !height.isBlank()) profile.append("Height: ").append(height).append('\n');
        if (build != null && !build.isBlank()) profile.append("Build: ").append(build).append('\n');
        if (hair != null && !hair.isBlank()) profile.append("Hair: ").append(hair).append('\n');
        if (eyes != null && !eyes.isBlank()) profile.append("Eyes: ").append(eyes).append('\n');
        if (!profile.isEmpty()) {
            prompt.append("\n\n=== YOUR PHYSICAL PROFILE ===\n").append(profile.toString().strip());
            hasDetails = true;
        }

        StringBuilder attributes = new StringBuilder();
        appendAttribute(attributes, "Strength", strength);
        appendAttribute(attributes, "Dexterity", dexterity);
        appendAttribute(attributes, "Constitution", constitution);
        appendAttribute(attributes, "Intelligence", intelligence);
        appendAttribute(attributes, "Wisdom", wisdom);
        appendAttribute(attributes, "Charisma", charisma);
        if (!attributes.isEmpty()) {
            prompt.append("\n\n=== YOUR BASE ATTRIBUTES ===\n")
                    .append("These are the limits of your body and mind. They use a 1-20 scale, where 10 is average. Stay inside them.\n")
                    .append(attributes.toString().strip());
            hasDetails = true;
        }

        if (backstory != null && !backstory.isEmpty()) {
            prompt.append("\n\n=== YOUR BACKGROUND ===\n").append(backstory);
            hasDetails = true;
        }

        if (goals != null && !goals.isEmpty()) {
            prompt.append("\n\n=== YOUR GOALS & MOTIVATIONS ===\n").append(goals);
            hasDetails = true;
        }

        if ((likes != null && !likes.isEmpty()) || (dislikes != null && !dislikes.isEmpty())) {
            prompt.append("\n\n=== YOUR PREFERENCES ===\n");
            if (likes != null && !likes.isEmpty()) {
                prompt.append("Things you enjoy: ").append(likes).append("\n");
            }
            if (dislikes != null && !dislikes.isEmpty()) {
                prompt.append("Things you dislike: ").append(dislikes);
            }
            hasDetails = true;
        }

        if (examples != null && !examples.isEmpty()) {
            prompt.append("\n\n=== EXAMPLE BEHAVIORS ===\n").append(examples);
            hasDetails = true;
        }

        // Add roleplay guidelines if we have character details
        if (hasDetails) {
            prompt.append("\n\n=== ROLEPLAY GUIDELINES ===\n");
            prompt.append("- Stay fully in character as ").append(charName).append(" at all times\n");
            prompt.append("- Respond naturally with emotions, reactions, and mannerisms authentic to your character\n");
            prompt.append("- Use speech patterns and vocabulary fitting your personality\n");
            prompt.append("- Reference your background and experiences when relevant\n");
            prompt.append("- Maintain consistency with your established traits and history\n");
            prompt.append("- Keep responses to ONE short paragraph maximum\n");
            prompt.append("- Write ONLY your own dialogue and physical actions\n");
            prompt.append("- NEVER describe the outcome or result of your actions — the Narrator decides what happens\n");
            prompt.append("- NEVER narrate the environment, scene, or world — that is the Narrator's role\n");
            prompt.append("- NEVER speak or act for other characters");
        }

        return prompt.toString();
    }
    
    /**
     * Update the last modified timestamp.
     */
    public void touch() {
        this.updatedAt = Time4JHelper.nowUTCString();
        this.lastModified = Time4JHelper.nowUTCString();
    }
    
    /**
     * Get creator (alias for owner).
     */
    public User getCreator() {
        return owner != null ? owner : creator;
    }
    
    /**
     * Set creator (sets owner).
     */
    public void setCreator(User creator) {
        this.creator = creator;
        this.owner = creator;
    }
    
    /**
     * Add a tag.
     */
    public void addTag(String tag) {
        if (tags == null) tags = new ArrayList<>();
        if (!tags.contains(tag)) {
            tags.add(tag);
            touch();
        }
    }
    
    /**
     * Remove a tag.
     */
    public void removeTag(String tag) {
        if (tags != null) {
            tags.remove(tag);
            touch();
        }
    }
    
    /**
     * Set tags from the stored column.
     *
     * The character writers store a JSON array, while older rows and the
     * comma-separated convention both exist. Read either form so a roster
     * member's tag does not reach the client as the literal text
     * {@code ["roster"]}.
     */
    public void setTagsFromString(String tagsString) {
        if (tagsString == null || tagsString.isBlank()) {
            this.tags = new ArrayList<>();
            return;
        }
        String trimmed = tagsString.strip();
        if (trimmed.startsWith("[")) {
            try {
                this.tags = new ArrayList<>();
                for (Object value : new io.vertx.core.json.JsonArray(trimmed)) {
                    if (value != null && !value.toString().isBlank()) {
                        this.tags.add(value.toString());
                    }
                }
                return;
            } catch (RuntimeException malformed) {
                // Fall through to the comma-separated form.
            }
        }
        this.tags = new ArrayList<>(List.of(trimmed.split("\\s*,\\s*")));
    }
    
    /**
     * Get tags as comma-separated string.
     */
    public String getTagsAsString() {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return String.join(",", tags);
    }
    
    /**
     * Update rating with new value.
     */
    public void updateRating(int newRating) {
        if (rating == null) rating = 0.0;
        if (usageCount == null) usageCount = 0;
        
        // Calculate new average rating
        double totalRating = rating * usageCount + newRating;
        usageCount++;
        rating = totalRating / usageCount;
        
        touch();
    }
    
    /**
     * Increment usage count.
     */
    public void incrementUsage() {
        if (usageCount == null) usageCount = 0;
        usageCount++;
        touch();
    }
    
    // Compatibility getter/setter methods
    public Boolean getPublic() {
        return publicField != null ? publicField : isPublic;
    }
    
    public void setPublic(Boolean value) {
        this.publicField = value;
        this.isPublic = value;
    }
    
    /**
     * Check if character is public.
     */
    public boolean isPublic() {
        return Boolean.TRUE.equals(getPublic());
    }
    
    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
        this.updatedAt = lastModified;
    }
    
    // Compatibility methods for userId
    public UUID getUserId() {
        return ownerId;
    }
    
    public void setUserId(UUID userId) {
        this.ownerId = userId;
    }
    
    // Methods for temperature as Float
    public Float getTemperatureFloat() {
        return temperature != null ? temperature.floatValue() : null;
    }
    
    public void setTemperatureFloat(Float temp) {
        this.temperature = temp != null ? temp.doubleValue() : null;
    }
    
    // Methods for topP as Float
    public Float getTopPFloat() {
        return topP != null ? topP.floatValue() : null;
    }
    
    public void setTopPFloat(Float value) {
        this.topP = value != null ? value.doubleValue() : null;
    }
    
    // Methods for repeatPenalty as Float
    public Float getRepeatPenaltyFloat() {
        return repeatPenalty != null ? repeatPenalty.floatValue() : null;
    }
    
    public void setRepeatPenaltyFloat(Float value) {
        this.repeatPenalty = value != null ? value.doubleValue() : null;
    }

    private static void appendAttribute(StringBuilder target, String label, Integer value) {
        if (value != null) target.append(label).append(": ").append(value).append('\n');
    }
}