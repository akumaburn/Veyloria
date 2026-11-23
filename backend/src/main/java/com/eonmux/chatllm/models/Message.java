package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import io.vertx.core.json.JsonObject;

import net.time4j.PlainTimestamp;
import net.time4j.format.expert.PatternType;
import net.time4j.format.expert.ChronoFormatter;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * Message entity matching the GraphQL schema and frontend expectations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {
    private UUID id;
    // Monotonic insertion-order sequence. Exposed via the GraphQL API so the
    // frontend can sort messages by exact insertion order instead of by the
    // second-resolution createdAt + a random-UUID tiebreaker (which misordered
    // messages sharing the same second). See schema.sql `message.seq`.
    private Long seq;
    private UUID conversationId;
    private UUID ownerId;
    private String content;
    private String contentBase64; // For Base64 encoded content
    @Builder.Default
    private MessageRole role = MessageRole.USER; // Enum for role
    private String displayName;
    @Builder.Default
    private Boolean isBase64Encoded = false;
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private Integer editCount = 0; // Resolved via MessageResolver @Source method
    @Builder.Default
    private Boolean isEdited = false; // New field
    @Builder.Default
    private Boolean isDeleted = false; // New field
    private String model;

    // Generation statistics
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer promptEvalMs;
    private Integer generationMs;
    private Integer totalTimeMs;
    private Double tokensPerSecond;
    private String createdAt;
    private String updatedAt;

    // Group chat fields
    private UUID characterId; // Which character sent this message (in group chat)
    @Builder.Default
    private Boolean isInterjection = false; // Was this an interjection message
    @Builder.Default
    private Boolean isStreaming = false;

    // Roleplay fields
    private UUID sceneId;                      // Scene this message belongs to
    @Builder.Default
    private MessageType messageType = MessageType.DIALOGUE;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject visibilityOverride;     // Explicit visibility for whispers/secrets
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject agentMetadata;          // Pipeline agent annotations

    public String getBattleId() {
        return agentMetadata == null ? null : agentMetadata.getString("battleId");
    }

    // Soft delete fields
    private String deletedAt;
    private String scheduledDeleteAt;

    // Relationships (resolved via MessageResolver @Source methods)
    @org.eclipse.microprofile.graphql.Ignore
    private transient Conversation conversation;
    @org.eclipse.microprofile.graphql.Ignore
    private transient User owner;
    @org.eclipse.microprofile.graphql.Ignore
    private transient User author; // Alias for owner
    @org.eclipse.microprofile.graphql.Ignore
    private transient Character character; // Character who sent this message (group chat)
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private transient List<MessageEdit> editHistory = new ArrayList<>();
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private transient List<Attachment> attachments = new ArrayList<>(); // Resolved via MessageResolver @Source method
    @org.eclipse.microprofile.graphql.Ignore
    private transient MessageMetadata metadata; // New field

    // Constants
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final int MAX_EDIT_COUNT = 10;
    public static final long MAX_EDIT_TIME_MINUTES = 5;
    private static final ChronoFormatter<PlainTimestamp> TS_FORMAT =
        ChronoFormatter.ofTimestampPattern("uuuu-MM-dd'T'HH:mm:ss", PatternType.CLDR, Locale.ROOT);

    /**
     * Get role as string for compatibility.
     */
    public String getRoleString() {
        return role != null ? role.toString().toLowerCase() : ROLE_USER;
    }

    /**
     * Set role from string.
     */
    public void setRoleString(String roleStr) {
        if (roleStr != null) {
            try {
                this.role = MessageRole.valueOf(roleStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                this.role = MessageRole.USER;
            }
        }
    }

    /**
     * Get userId (alias for ownerId for GraphQL).
     */
    public UUID getUserId() {
        return ownerId;
    }

    /**
     * Get author (alias for owner).
     */
    public User getAuthor() {
        return owner != null ? owner : author;
    }

    /**
     * Set author (sets owner).
     */
    public void setAuthor(User author) {
        this.author = author;
        this.owner = author;
    }

    /**
     * Get metadata object.
     */
    public MessageMetadata getMetadata() {
        if (metadata == null) {
            metadata = new MessageMetadata();
            metadata.setModel(model);
            metadata.setCompletionTokens(completionTokens);
            metadata.setTotalTimeMs(totalTimeMs);
            metadata.setEdited(isEdited);
            metadata.setEditedAt(isEdited ? updatedAt : null);
        }
        return metadata;
    }

    /**
     * Get decoded content if Base64 encoded.
     */
    public String getDecodedContent() {
        if (contentBase64 != null && !contentBase64.isEmpty()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(contentBase64);
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // If decoding fails, return original content
                return content;
            }
        }
        if (Boolean.TRUE.equals(isBase64Encoded) && content != null) {
            try {
                byte[] decoded = Base64.getDecoder().decode(content);
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // If decoding fails, return original content
                return content;
            }
        }
        return content;
    }

    /**
     * Set content with optional Base64 encoding.
     */
    public void setEncodedContent(String content, boolean encode) {
        if (encode && content != null) {
            String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            this.contentBase64 = encoded;
            this.content = content; // Keep original for backward compatibility
            this.isBase64Encoded = true;
        } else {
            this.content = content;
            this.contentBase64 = null;
            this.isBase64Encoded = false;
        }
    }

    /**
     * Check if message is from user.
     */
    public boolean isUserMessage() {
        return MessageRole.USER.equals(role);
    }

    /**
     * Check if message is from assistant.
     */
    public boolean isAssistantMessage() {
        return MessageRole.ASSISTANT.equals(role);
    }

    /**
     * Check if message is system message.
     */
    public boolean isSystemMessage() {
        return MessageRole.SYSTEM.equals(role);
    }

    /**
     * Check if message can be edited.
     */
    public boolean canEdit() {
        if (Boolean.TRUE.equals(isDeleted)) {
            return false;
        }
        if (editCount != null && editCount >= MAX_EDIT_COUNT) {
            return false;
        }
        if (createdAt != null) {
            try {
                String editDeadline = TS_FORMAT.parse(createdAt).plus(MAX_EDIT_TIME_MINUTES, net.time4j.ClockUnit.MINUTES).toString();
                return Time4JHelper.nowUTCString().compareTo(editDeadline) < 0;
            } catch (Exception e) {
                return true;
            }
        }
        return true;
    }

    /**
     * Mark message as edited.
     */
    public void markAsEdited() {
        this.isEdited = true;
        incrementEditCount();
    }

    /**
     * Mark message as deleted.
     */
    public void markAsDeleted() {
        this.isDeleted = true;
        this.updatedAt = Time4JHelper.nowUTCString();
    }

    /**
     * Increment edit count.
     */
    public void incrementEditCount() {
        if (editCount == null) {
            editCount = 1;
        } else {
            editCount++;
        }
        updatedAt = Time4JHelper.nowUTCString();
    }

    /**
     * Get display name or role if not set.
     */
    public String getEffectiveDisplayName() {
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        if (role != null) {
            switch (role) {
                case USER:
                    return "User";
                case ASSISTANT:
                    return "Assistant";
                case SYSTEM:
                    return "System";
                default:
                    return role.toString();
            }
        }
        return "Unknown";
    }

    /**
     * Add an attachment.
     */
    public void addAttachment(Attachment attachment) {
        if (attachments == null) attachments = new ArrayList<>();
        attachments.add(attachment);
    }

    /**
     * Remove an attachment.
     */
    public void removeAttachment(String attachmentId) {
        if (attachments != null) {
            attachments.removeIf(a -> a.getId().toString().equals(attachmentId));
        }
    }

    /**
     * Set processing metrics.
     */
    public void setProcessingMetrics(String model, int completionTokens, int totalTimeMs) {
        this.model = model;
        this.completionTokens = completionTokens;
        this.totalTimeMs = totalTimeMs;
    }

    /**
     * Set full generation statistics.
     */
    public void setGenerationStats(int promptTokens, int completionTokens,
                                   int promptEvalMs, int generationMs,
                                   int totalTimeMs, double tokensPerSecond) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.promptEvalMs = promptEvalMs;
        this.generationMs = generationMs;
        this.totalTimeMs = totalTimeMs;
        this.tokensPerSecond = tokensPerSecond;
    }

    /**
     * Get generation statistics as a GenerationStats object.
     * Returns null if no stats are available.
     */
    public GenerationStats getGenerationStats() {
        java.util.function.Predicate<Integer> hasValue = v -> v != null && v > 0;
        java.util.function.Predicate<Double> hasDoubleValue = v -> v != null && v > 0.0;

        boolean hasStats = hasValue.test(completionTokens) || hasValue.test(totalTimeMs) || hasDoubleValue.test(tokensPerSecond);
        if (!hasStats) {
            return null;
        }

        int effectiveCompletionTokens = hasValue.test(completionTokens) ? completionTokens : 0;
        int effectivePromptTokens = hasValue.test(promptTokens) ? promptTokens : 0;
        int effectiveTotalTimeMs = hasValue.test(totalTimeMs) ? totalTimeMs : 0;
        int effectiveGenerationMs = hasValue.test(generationMs) ? generationMs : effectiveTotalTimeMs;
        int effectivePromptEvalMs = hasValue.test(promptEvalMs) ? promptEvalMs : 0;
        double effectiveTps = hasDoubleValue.test(tokensPerSecond) ? tokensPerSecond :
                              (effectiveCompletionTokens > 0 && effectiveGenerationMs > 0 ?
                               (double) effectiveCompletionTokens / (effectiveGenerationMs / 1000.0) : 0.0);

        return GenerationStats.builder()
                .promptTokens(effectivePromptTokens)
                .completionTokens(effectiveCompletionTokens)
                .totalTokens(effectivePromptTokens + effectiveCompletionTokens)
                .promptEvalMs(effectivePromptEvalMs)
                .generationMs(effectiveGenerationMs)
                .totalTimeMs(effectiveTotalTimeMs)
                .tokensPerSecond(effectiveTps)
                .build();
    }

    /**
     * Check if message has generation statistics.
     */
    public boolean hasGenerationStats() {
        return (completionTokens != null && completionTokens > 0) ||
               (totalTimeMs != null && totalTimeMs > 0) ||
               (tokensPerSecond != null && tokensPerSecond > 0.0);
    }
}