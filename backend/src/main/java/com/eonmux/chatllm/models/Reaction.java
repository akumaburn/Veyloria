package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.UUID;

/**
 * Reaction entity for message reactions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reaction {
    private UUID id;
    private UUID messageId;
    private UUID userId;
    private String reactionType;
    private String createdAt;

    // Related user (not currently exposed to GraphQL — frontend reads userId directly)
    @org.eclipse.microprofile.graphql.Ignore
    private transient User user;
}
