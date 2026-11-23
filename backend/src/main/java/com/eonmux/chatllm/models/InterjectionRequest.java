package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * InterjectionRequest entity for group chat.
 * Represents a request to interject (speak out of turn) in a group conversation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterjectionRequest {
    private UUID id;
    private UUID conversationId;
    private UUID requesterId; // Can be null for user requests
    private String requesterType; // "user" or "character"
    private String reason;
    @Builder.Default
    private Integer priority = 0;
    @Builder.Default
    private String status = "pending"; // pending, approved, consumed, rejected, expired
    private String createdAt;
    private String resolvedAt;

    // Relationships (for GraphQL field resolution)
    @org.eclipse.microprofile.graphql.Ignore
    private transient Conversation conversation;
    private Character requester;

    /**
     * Check if this is a user interjection request.
     */
    public boolean isUserRequest() {
        return "user".equalsIgnoreCase(requesterType);
    }

    /**
     * Check if this is a character interjection request.
     */
    public boolean isCharacterRequest() {
        return "character".equalsIgnoreCase(requesterType);
    }

    /**
     * Check if the request is still pending.
     */
    public boolean isPending() {
        return "pending".equalsIgnoreCase(status);
    }

    /**
     * Approve the interjection request.
     */
    public void approve() {
        this.status = "approved";
        this.resolvedAt = Time4JHelper.nowUTCString();
    }
    
    /**
     * Reject the interjection request.
     */
    public void reject() {
        this.status = "rejected";
        this.resolvedAt = Time4JHelper.nowUTCString();
    }
    
    /**
     * Mark the request as expired.
     */
    public void expire() {
        this.status = "expired";
        this.resolvedAt = Time4JHelper.nowUTCString();
    }

    /**
     * Compare priorities for ordering.
     * Handles null values safely: null priority treated as 0, null createdAt sorted last.
     */
    public int comparePriority(InterjectionRequest other) {
        // Higher priority first, then by creation time
        // Handle null priority by treating as 0
        int thisPriority = this.priority != null ? this.priority : 0;
        int otherPriority = other.priority != null ? other.priority : 0;
        int priorityCompare = Integer.compare(otherPriority, thisPriority);
        if (priorityCompare != 0) return priorityCompare;

        // Handle null createdAt - sort nulls last
        if (this.createdAt == null && other.createdAt == null) return 0;
        if (this.createdAt == null) return 1;  // this is later (sorted last)
        if (other.createdAt == null) return -1; // other is later
        return this.createdAt.compareTo(other.createdAt);
    }
}
