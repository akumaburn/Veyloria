package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * ConversationInvite entity for inviting users to conversations.
 * Supports invites by user ID, username, or email.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationInvite {
    private UUID id;
    private UUID conversationId;
    private UUID inviterId;      // User who sent the invite
    private UUID inviteeId;      // User being invited (null if email-only invite)
    private String inviteeEmail; // Email for non-registered users
    private String inviteeUsername; // Username lookup convenience
    @Builder.Default
    private InviteStatus status = InviteStatus.PENDING;
    private String message;      // Optional message from inviter
    @Builder.Default
    private ConversationParticipant.ParticipantRole roleToGrant = ConversationParticipant.ParticipantRole.MEMBER;
    private String createdAt;
    private String respondedAt;
    private String expiresAt;

    // Relationships (resolved via InviteResolver @Source methods — fields are
    // eagerly populated by mapRowToInvite() joins and consumed by the resolvers).
    @org.eclipse.microprofile.graphql.Ignore
    private transient Conversation conversation;
    @org.eclipse.microprofile.graphql.Ignore
    private transient User inviter;
    @org.eclipse.microprofile.graphql.Ignore
    private transient User invitee;

    /**
     * Status enum for invite lifecycle.
     */
    public enum InviteStatus {
        PENDING,   // Awaiting response
        ACCEPTED,  // Invite accepted
        DECLINED,  // Invite declined
        EXPIRED,   // Invite expired (past expiresAt)
        CANCELLED;  // Invite cancelled by inviter

        /**
         * Parse status from string, case-insensitive.
         */
        public static InviteStatus fromString(String value) {
            if (value == null || value.isEmpty()) {
                return PENDING;
            }
            try {
                return InviteStatus.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return PENDING;
            }
        }
    }

    /**
     * Check if invite is still valid (pending and not expired).
     */
    public boolean isValid() {
        if (status != InviteStatus.PENDING) {
            return false;
        }
        if (expiresAt != null && Time4JHelper.nowUTCString().compareTo(expiresAt) > 0) {
            return false;
        }
        return true;
    }

    /**
     * Accept the invite.
     */
    public void accept() {
        this.status = InviteStatus.ACCEPTED;
        this.respondedAt = Time4JHelper.nowUTCString();
    }
    
    /**
     * Decline the invite.
     */
    public void decline() {
        this.status = InviteStatus.DECLINED;
        this.respondedAt = Time4JHelper.nowUTCString();
    }
    
    /**
     * Cancel the invite (by inviter).
     */
    public void cancel() {
        this.status = InviteStatus.CANCELLED;
        this.respondedAt = Time4JHelper.nowUTCString();
    }

    /**
     * Mark as expired.
     */
    public void expire() {
        this.status = InviteStatus.EXPIRED;
    }

    /**
     * Check if invite has expired.
     */
    public boolean hasExpired() {
        return expiresAt != null && Time4JHelper.nowUTCString().compareTo(expiresAt) > 0;
    }

    /**
     * Check if invite is pending.
     */
    public boolean isPending() {
        return status == InviteStatus.PENDING;
    }

    /**
     * Get invite target identifier (username, email, or user ID).
     */
    public String getTargetIdentifier() {
        if (inviteeUsername != null && !inviteeUsername.isEmpty()) {
            return inviteeUsername;
        }
        if (inviteeEmail != null && !inviteeEmail.isEmpty()) {
            return inviteeEmail;
        }
        if (inviteeId != null) {
            return inviteeId.toString();
        }
        return "Unknown";
    }

    /**
     * Create a participant from this invite when accepted.
     */
    public ConversationParticipant createParticipant() {
        return ConversationParticipant.builder()
                .id(UUID.randomUUID())
                .conversationId(conversationId)
                .userId(inviteeId)
                .role(roleToGrant)
                .isActive(true)
                .canInvite(roleToGrant == ConversationParticipant.ParticipantRole.ADMIN ||
                          roleToGrant == ConversationParticipant.ParticipantRole.OWNER)
                .canAddCharacters(roleToGrant == ConversationParticipant.ParticipantRole.ADMIN ||
                                 roleToGrant == ConversationParticipant.ParticipantRole.OWNER)
                .joinedAt(Time4JHelper.nowUTCString())
                .build();
    }

    /**
     * Set default expiration (7 days from now).
     */
    public void setDefaultExpiration() {
        this.expiresAt = Time4JHelper.nowUTC().plus(7, net.time4j.CalendarUnit.DAYS).toString();
    }
}
