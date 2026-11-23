package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * ConversationCharacter entity for group chat - junction table between Conversation and Character.
 * Tracks each character's participation in a group conversation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationCharacter {
    private UUID id;
    private UUID conversationId;
    private UUID characterId;
    private String joinedAt;
    @Builder.Default
    private Boolean isActive = true;
    @Builder.Default
    private Integer turnOrder = 0;
    @Builder.Default
    private Integer responseCount = 0;
    private String lastResponseAt;
    @Builder.Default
    private Double weight = 1.0; // For weighted random selection
    @Builder.Default
    private Double interjectionProbability = 0.1; // Probability of spontaneous interjection
    /** False until the story brings this roster member onstage. */
    @Builder.Default
    private Boolean isIntroduced = true;
    private String introducedAt;

    // Relationships (for GraphQL field resolution)
    private transient Conversation conversation;
    private transient Character character;

    /**
     * Increment response count and update last response time.
     */
    public void recordResponse() {
        this.responseCount++;
        this.lastResponseAt = Time4JHelper.nowUTCString();
    }

    /**
     * Check if character can interject based on probability.
     * Returns false if interjectionProbability is null.
     */
    public boolean shouldInterject() {
        return interjectionProbability != null && Math.random() < interjectionProbability;
    }

    /**
     * Deactivate character from the conversation.
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * Reactivate character in the conversation.
     */
    public void activate() {
        this.isActive = true;
    }
}
