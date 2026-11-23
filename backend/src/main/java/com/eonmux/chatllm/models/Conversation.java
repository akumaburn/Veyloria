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
 * Conversation entity matching the GraphQL schema and frontend expectations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation {
    private UUID id;
    private UUID ownerId;
    private UUID userId; // Alias for ownerId
    private UUID characterId;
    private String title;
    private String description; // New field
    @Builder.Default
    private Boolean isPinned = false; // New field
    @Builder.Default
    private Boolean isArchived = false; // New field
    private String category; // New field
    @Builder.Default
    private List<String> tags = new ArrayList<>(); // New field
    private String createdAt;
    private String updatedAt;
    private String lastModified;
    private String lastMessageTime;
    private String lastMessageAt; // New field (alias)
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private Integer messageCount = 0; // Resolved via ConversationResolver @Source method
    private Boolean active;
    private String characterName;
    private String characterDescription;

    // Group chat fields
    @Builder.Default
    private Boolean isGroupChat = false;
    @Builder.Default
    private String turnMode = "RANDOM"; // RANDOM, ROUND_ROBIN, WEIGHTED
    private UUID currentSpeakerId;
    private UUID lastSpeakerId;
    @Builder.Default
    private Integer maxConsecutiveResponses = 3;
    @Builder.Default
    private Integer consecutiveResponseCount = 0;
    @Builder.Default
    private String groupState = "WAITING_FOR_USER"; // WAITING_FOR_USER, CHARACTER_RESPONDING, PAUSED, INTERJECTION_PENDING

    @org.eclipse.microprofile.graphql.Ignore
    private transient ConversationProgress processingStatus;
    private transient Boolean processingOnly;

    // Roleplay fields
    @Builder.Default
    private Boolean isRoleplay = false;
    private String storyTemplateId;
    private UUID activeSceneId;
    @Builder.Default
    private String rpMode = "freeform"; // freeform, guided, strict
    @Builder.Default
    private Boolean agentPipelineEnabled = true;

    // Relationships (resolved via ConversationResolver @Source methods)
    @org.eclipse.microprofile.graphql.Ignore
    private transient User owner;
    @org.eclipse.microprofile.graphql.Ignore
    private transient Character character;
    @org.eclipse.microprofile.graphql.Ignore
    private transient Character currentSpeaker;
    @org.eclipse.microprofile.graphql.Ignore
    private transient Character lastSpeaker;
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private transient List<Message> messages = new ArrayList<>();
    @org.eclipse.microprofile.graphql.Ignore
    private transient Message lastMessage;
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private transient List<ConversationCharacter> characters = new ArrayList<>();
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private transient List<InterjectionRequest> pendingInterjections = new ArrayList<>();
    // Multi-user support
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private transient List<ConversationParticipant> participants = new ArrayList<>();
    @Builder.Default
    private transient List<ConversationInvite> pendingInvites = new ArrayList<>();
    private transient ConversationParticipant myParticipation; // Current user's participation

    /**
     * Get turnMode, normalized to UPPER_CASE for GraphQL compatibility.
     */
    public String getTurnMode() {
        return turnMode != null ? turnMode.toUpperCase() : "RANDOM";
    }

    /**
     * Get groupState, normalized to UPPER_CASE for GraphQL compatibility.
     */
    public String getGroupState() {
        return groupState != null ? groupState.toUpperCase() : "WAITING_FOR_USER";
    }

    /**
     * Get lastMessageAt (alias for lastMessageTime).
     */
    public String getLastMessageAt() {
        return lastMessageTime != null ? lastMessageTime : lastMessageAt;
    }

    /**
     * Set lastMessageAt (sets lastMessageTime).
     */
    public void setLastMessageAt(String lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
        this.lastMessageTime = lastMessageAt;
    }

    /**
     * Get message count.
     */
    public Integer getMessageCount() {
        if (messageCount != null) return messageCount;
        if (messages != null) return messages.size();
        return 0;
    }

    /**
     * Check if conversation has a character.
     */
    public boolean hasCharacter() {
        return characterId != null;
    }

    /**
     * Check if conversation is empty.
     */
    public boolean isEmpty() {
        return getMessageCount() == 0;
    }

    /**
     * Get conversation title or generate one.
     */
    public String getEffectiveTitle() {
        if (title != null && !title.isEmpty()) {
            return title;
        }
        if (character != null && character.getName() != null) {
            return "Chat with " + character.getName();
        }
        if (lastMessage != null && lastMessage.getContent() != null) {
            String content = lastMessage.getContent();
            return content.length() > 50 ? content.substring(0, 50) + "..." : content;
        }
        return "New Conversation";
    }

    /**
     * Update the last modified timestamp.
     */
    public void touch() {
        this.updatedAt = Time4JHelper.nowUTCString();
        this.lastModified = Time4JHelper.nowUTCString();
    }

    /**
     * Pin the conversation.
     */
    public void pin() {
        this.isPinned = true;
        touch();
    }

    /**
     * Unpin the conversation.
     */
    public void unpin() {
        this.isPinned = false;
        touch();
    }

    /**
     * Archive the conversation.
     */
    public void archive() {
        this.isArchived = true;
        touch();
    }

    /**
     * Unarchive the conversation.
     */
    public void unarchive() {
        this.isArchived = false;
        touch();
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
     * Check if this is a multi-user conversation.
     */
    public boolean isMultiUser() {
        return participants != null && participants.size() > 1;
    }

    /**
     * Get active participant count.
     */
    public int getActiveParticipantCount() {
        if (participants == null) return 0;
        return (int) participants.stream()
                .filter(ConversationParticipant::getIsActive)
                .count();
    }

    /**
     * Get active character count.
     */
    public int getActiveCharacterCount() {
        if (characters == null) return 0;
        return (int) characters.stream()
                .filter(ConversationCharacter::getIsActive)
                .count();
    }

    /**
     * Check if a user is a participant.
     */
    public boolean hasParticipant(UUID userId) {
        if (participants == null) return false;
        return participants.stream()
                .anyMatch(p -> p.getUserId().equals(userId) && p.getIsActive());
    }

    /**
     * Check if a user is the owner.
     */
    public boolean isOwner(UUID userId) {
        return ownerId != null && ownerId.equals(userId);
    }

    /**
     * Check if a user has admin permissions.
     */
    public boolean hasAdminPermission(UUID userId) {
        if (isOwner(userId)) return true;
        if (participants == null) return false;
        return participants.stream()
                .filter(p -> p.getUserId().equals(userId) && p.getIsActive())
                .anyMatch(ConversationParticipant::hasAdminPermissions);
    }

    /**
     * Check if user can invite others.
     */
    public boolean canInvite(UUID userId) {
        if (isOwner(userId)) return true;
        if (participants == null) return false;
        return participants.stream()
                .filter(p -> p.getUserId().equals(userId) && p.getIsActive())
                .anyMatch(p -> p.getCanInvite() || p.hasAdminPermissions());
    }

    /**
     * Check if user can add characters.
     */
    public boolean canAddCharacters(UUID userId) {
        if (isOwner(userId)) return true;
        if (participants == null) return false;
        return participants.stream()
                .filter(p -> p.getUserId().equals(userId) && p.getIsActive())
                .anyMatch(p -> p.getCanAddCharacters() || p.hasAdminPermissions());
    }
}