package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * ConversationParticipant entity for multi-user conversations.
 * Junction table between Conversation and User - tracks each user's participation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationParticipant {
    private UUID id;
    private UUID conversationId;
    private UUID userId;
    @Builder.Default
    private ParticipantRole role = ParticipantRole.MEMBER;
    private String nickname; // Optional display name in this conversation
    @Builder.Default
    private Boolean isActive = true;
    @Builder.Default
    private Boolean canInvite = false;
    @Builder.Default
    private Boolean canAddCharacters = false;
    private String joinedAt;
    private String leftAt;
    private String lastReadAt;
    private String lastTypingAt;

    // Relationships (for GraphQL field resolution)
    @org.eclipse.microprofile.graphql.Ignore
    private transient Conversation conversation;
    private User user;

    // Computed field
    @org.eclipse.microprofile.graphql.Ignore
    private transient Integer unreadCount;

    /**
     * Role enum for participant permissions.
     */
    public enum ParticipantRole {
        OWNER,  // Full control, can delete conversation
        ADMIN,  // Can manage members and settings
        MEMBER;  // Can send messages only

        /**
         * Parse role from string, case-insensitive.
         */
        public static ParticipantRole fromString(String value) {
            if (value == null || value.isEmpty()) {
                return MEMBER;
            }
            try {
                return ParticipantRole.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return MEMBER;
            }
        }
    }

    /**
     * Check if participant has admin-level permissions.
     */
    public boolean hasAdminPermissions() {
        return role == ParticipantRole.OWNER || role == ParticipantRole.ADMIN;
    }

    /**
     * Check if participant is the owner.
     */
    public boolean isOwner() {
        return role == ParticipantRole.OWNER;
    }

    /**
     * Leave the conversation (soft delete).
     */
    public void leave() {
        this.isActive = false;
        this.leftAt = Time4JHelper.nowUTCString();
    }
    
    /**
     * Rejoin the conversation.
     */
    public void rejoin() {
        this.isActive = true;
        this.leftAt = null;
        this.joinedAt = Time4JHelper.nowUTCString();
    }

    /**
     * Update last read timestamp.
     */
    public void markAsRead() {
        this.lastReadAt = Time4JHelper.nowUTCString();
    }

    /**
     * Update typing indicator timestamp.
     */
    public void updateTyping() {
        this.lastTypingAt = Time4JHelper.nowUTCString();
    }

    /**
     * Grant admin role.
     */
    public void promoteToAdmin() {
        if (this.role != ParticipantRole.OWNER) {
            this.role = ParticipantRole.ADMIN;
            this.canInvite = true;
            this.canAddCharacters = true;
        }
    }

    /**
     * Demote to member role.
     */
    public void demoteToMember() {
        if (this.role == ParticipantRole.ADMIN) {
            this.role = ParticipantRole.MEMBER;
            this.canInvite = false;
            this.canAddCharacters = false;
        }
    }

    /**
     * Get display name (nickname or user's display name).
     */
    public String getDisplayName() {
        if (nickname != null && !nickname.isEmpty()) {
            return nickname;
        }
        if (user != null && user.getDisplayName() != null) {
            return user.getDisplayName();
        }
        if (user != null) {
            return user.getUsername();
        }
        return "Unknown User";
    }
}
