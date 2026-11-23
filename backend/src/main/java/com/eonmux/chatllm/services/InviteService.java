package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.*;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing conversation invitations and participants.
 * Handles invite lifecycle, participant management, and permissions.
 */
@ApplicationScoped
@Slf4j
public class InviteService {

    private static final int DEFAULT_INVITE_EXPIRY_DAYS = 7;
    private static final int MAX_PENDING_INVITES_PER_USER = 100;
    // Roles that can be granted via an invite. OWNER is deliberately excluded:
    // ownership transfer is a separate, audited flow (transferOwnership).
    private static final Set<String> GRANTABLE_ROLES = Set.of("MEMBER", "ADMIN");

    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    // EventBus obtained from vertx.eventBus()

    // ==========================================
    // INVITE OPERATIONS
    // ==========================================

    /**
     * Invite a user to a conversation by username or email.
     */
    public Uni<ConversationInvite> inviteUser(
            String inviterId,
            String conversationId,
            String usernameOrEmail,
            String message,
            String roleToGrant) {

        // Find the user by username or email
        return findUserByUsernameOrEmail(usernameOrEmail)
                .chain(invitee -> {
                    if (invitee == null) {
                        return Uni.createFrom().failure(new RuntimeException("User not found: " + usernameOrEmail));
                    }

                    // Check if user is already a participant
                    return isParticipant(conversationId, invitee.getId().toString())
                            .chain(isParticipant -> {
                                if (isParticipant) {
                                    return Uni.createFrom().failure(new RuntimeException("User is already a participant in this conversation"));
                                }

                                // Check for existing pending invite
                                return hasPendingInvite(conversationId, invitee.getId().toString())
                                        .chain(hasPending -> {
                                            if (hasPending) {
                                                return Uni.createFrom().failure(new RuntimeException("User already has a pending invite for this conversation"));
                                            }

                                            // Check inviter permissions
                                            return canInvite(conversationId, inviterId)
                                                    .chain(canInvite -> {
                                                        if (!canInvite) {
                                                            return Uni.createFrom().failure(new RuntimeException("You don't have permission to invite users to this conversation"));
                                                        }

                                                        // Validate the role being granted. Elevated roles (ADMIN) require
                                                        // admin permission; OWNER can never be granted via an invite.
                                                        String role = roleToGrant != null ? roleToGrant.trim().toUpperCase() : "MEMBER";
                                                        if (!GRANTABLE_ROLES.contains(role)) {
                                                            return Uni.createFrom().failure(new RuntimeException("Invalid role to grant: " + roleToGrant));
                                                        }
                                                        if ("ADMIN".equals(role)) {
                                                            return hasAdminPermission(conversationId, inviterId)
                                                                    .chain(isAdmin -> {
                                                                        if (!isAdmin) {
                                                                            return Uni.createFrom().failure(new RuntimeException("Only owners or admins can invite with the ADMIN role"));
                                                                        }
                                                                        return createInvite(inviterId, conversationId, invitee, message, role);
                                                                    });
                                                        }
                                                        return createInvite(inviterId, conversationId, invitee, message, role);
                                                    });
                                        });
                            });
                });
    }

    /**
     * Create the invite record.
     */
    private Uni<ConversationInvite> createInvite(
            String inviterId,
            String conversationId,
            User invitee,
            String message,
            String roleToGrant) {

        String inviteId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusDays(DEFAULT_INVITE_EXPIRY_DAYS);
        String role = roleToGrant != null ? roleToGrant.toUpperCase() : "MEMBER";

        String insertQuery = """
            INSERT INTO conversation_invite (
                id, conversation_id, inviter_id, invitee_id, invitee_email,
                invitee_username, status, message, role_to_grant, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        return pool.preparedQuery(insertQuery)
                .execute(Tuple.tuple()
                        .addValue(inviteId)
                        .addValue(conversationId)
                        .addValue(inviterId)
                        .addValue(invitee.getId().toString())
                        .addValue(invitee.getEmail())
                        .addValue(invitee.getUsername())
                        .addValue("pending")
                        .addValue(message)
                        .addValue(role)
                        .addValue(now)
                        .addValue(expiresAt))
                .chain(result -> getInviteById(inviteId))
                .invoke(invite -> {
                    // Publish event for real-time notification
                    JsonObject event = new JsonObject()
                            .put("inviteId", inviteId)
                            .put("conversationId", conversationId)
                            .put("inviteeId", invitee.getId().toString());
                    vertx.eventBus().publish("invite.received." + invitee.getId().toString(), event);
                    log.info("Created invite {} for user {} to conversation {}",
                            inviteId, invitee.getUsername(), conversationId);
                });
    }

    /**
     * Accept an invite.
     * Uses optimistic locking to prevent race conditions.
     */
    public Uni<ConversationInvite> acceptInvite(String inviteId, String userId) {
        return getInviteById(inviteId)
                .chain(invite -> {
                    if (invite == null) {
                        return Uni.createFrom().failure(new RuntimeException("Invite not found"));
                    }

                    if (!invite.getInviteeId().toString().equals(userId)) {
                        return Uni.createFrom().failure(new RuntimeException("This invite is not for you"));
                    }

                    if (!invite.isPending()) {
                        return Uni.createFrom().failure(new RuntimeException("Invite has already been " + invite.getStatus().name().toLowerCase()));
                    }

                    if (invite.hasExpired()) {
                        return expireInvite(inviteId)
                                .chain(v -> Uni.createFrom().failure(new RuntimeException("Invite has expired")));
                    }

                    // Use optimistic locking - only update if still pending
                    // This prevents race conditions when multiple requests try to accept/decline simultaneously
                    String updateQuery = """
                        UPDATE conversation_invite
                        SET status = 'accepted', responded_at = ?
                        WHERE id = ? AND status = 'pending'
                        AND (expires_at IS NULL OR expires_at > NOW())
                        """;

                    return pool.preparedQuery(updateQuery)
                            .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), inviteId))
                            .chain(result -> {
                                // Check if we actually updated the row (optimistic lock check)
                                if (result.rowCount() == 0) {
                                    // Another request already processed this invite.
                                    // If a *different* user accepted it, the current user must
                                    // NOT be reported as having joined.
                                    return getInviteById(inviteId)
                                            .chain(currentInvite -> {
                                                if (currentInvite != null
                                                        && currentInvite.getStatus() == ConversationInvite.InviteStatus.ACCEPTED
                                                        && currentInvite.getInviteeId().toString().equals(userId)) {
                                                    // Already accepted by this same user - idempotent success
                                                    return Uni.createFrom().item(currentInvite);
                                                }
                                                return Uni.createFrom().failure(new RuntimeException("Invite is no longer available"));
                                            });
                                }
                                // Add user as participant
                                return addParticipant(
                                        invite.getConversationId().toString(),
                                        userId,
                                        invite.getRoleToGrant().name()
                                ).chain(participant -> getInviteById(inviteId));
                            })
                            .invoke(acceptedInvite -> {
                                // Publish events
                                JsonObject event = new JsonObject()
                                        .put("inviteId", inviteId)
                                        .put("conversationId", invite.getConversationId().toString())
                                        .put("userId", userId)
                                        .put("status", "accepted");
                                vertx.eventBus().publish("invite.updated." + inviteId, event);
                                vertx.eventBus().publish("participant.joined." + invite.getConversationId().toString(), event);
                                log.info("User {} accepted invite {}", userId, inviteId);
                            });
                });
    }

    /**
     * Decline an invite.
     * Uses optimistic locking to prevent race conditions.
     */
    public Uni<ConversationInvite> declineInvite(String inviteId, String userId) {
        return getInviteById(inviteId)
                .chain(invite -> {
                    if (invite == null) {
                        return Uni.createFrom().failure(new RuntimeException("Invite not found"));
                    }

                    if (!invite.getInviteeId().toString().equals(userId)) {
                        return Uni.createFrom().failure(new RuntimeException("This invite is not for you"));
                    }

                    if (!invite.isPending()) {
                        return Uni.createFrom().failure(new RuntimeException("Invite has already been " + invite.getStatus().name().toLowerCase()));
                    }

                    // Use optimistic locking - only update if still pending
                    String updateQuery = """
                        UPDATE conversation_invite
                        SET status = 'declined', responded_at = ?
                        WHERE id = ? AND status = 'pending'
                        """;

                    return pool.preparedQuery(updateQuery)
                            .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), inviteId))
                            .chain(result -> {
                                if (result.rowCount() == 0) {
                                    return Uni.createFrom().failure(new RuntimeException("Invite is no longer available"));
                                }
                                return getInviteById(inviteId);
                            })
                            .invoke(declinedInvite -> {
                                JsonObject event = new JsonObject()
                                        .put("inviteId", inviteId)
                                        .put("status", "declined");
                                vertx.eventBus().publish("invite.updated." + inviteId, event);
                                log.info("User {} declined invite {}", userId, inviteId);
                            });
                });
    }

    /**
     * Cancel an invite (by inviter or admin).
     * Uses optimistic locking to prevent race conditions.
     */
    public Uni<Boolean> cancelInvite(String inviteId, String userId) {
        return getInviteById(inviteId)
                .chain(invite -> {
                    if (invite == null) {
                        return Uni.createFrom().failure(new RuntimeException("Invite not found"));
                    }

                    if (!invite.isPending()) {
                        return Uni.createFrom().failure(new RuntimeException("Invite has already been " + invite.getStatus().name().toLowerCase()));
                    }

                    // Check if user is inviter or has admin rights
                    return canCancelInvite(invite, userId)
                            .chain(canCancel -> {
                                if (!canCancel) {
                                    return Uni.createFrom().failure(new RuntimeException("You don't have permission to cancel this invite"));
                                }

                                // Use optimistic locking - only cancel if still pending
                                String updateQuery = """
                                    UPDATE conversation_invite
                                    SET status = 'cancelled', responded_at = ?
                                    WHERE id = ? AND status = 'pending'
                                    """;

                                return pool.preparedQuery(updateQuery)
                                        .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), inviteId))
                                        .map(result -> {
                                            if (result.rowCount() == 0) {
                                                return false; // Already processed
                                            }
                                            return true;
                                        })
                                        .invoke(success -> {
                                            if (success) {
                                                JsonObject event = new JsonObject()
                                                        .put("inviteId", inviteId)
                                                        .put("status", "cancelled");
                                                vertx.eventBus().publish("invite.updated." + inviteId, event);
                                                log.info("Invite {} cancelled by user {}", inviteId, userId);
                                            }
                                        });
                            });
                });
    }

    /**
     * Expire an invite.
     */
    private Uni<Void> expireInvite(String inviteId) {
        String updateQuery = """
            UPDATE conversation_invite
            SET status = 'expired'
            WHERE id = ?
            """;

        return pool.preparedQuery(updateQuery)
                .execute(Tuple.of(inviteId))
                .replaceWithVoid();
    }

    /**
     * Get invite by ID with relationships.
     */
    public Uni<ConversationInvite> getInviteById(String inviteId) {
        String query = """
            SELECT ci.*,
                   c.conversationGUID as conv_id, c.title as conv_title,
                   u1.userUUID as inviter_uuid, u1.username as inviter_username,
                   u1.displayName as inviter_display, u1.profilePicture as inviter_pic,
                   u2.userUUID as invitee_uuid, u2.username as invitee_username,
                   u2.displayName as invitee_display, u2.profilePicture as invitee_pic
            FROM conversation_invite ci
            LEFT JOIN conversation c ON ci.conversation_id = c.conversationGUID
            LEFT JOIN user u1 ON ci.inviter_id = u1.userUUID
            LEFT JOIN user u2 ON ci.invitee_id = u2.userUUID
            WHERE ci.id = ?
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(inviteId))
                .map(rows -> {
                    for (Row row : rows) {
                        return mapRowToInvite(row);
                    }
                    return null;
                });
    }

    /**
     * Get pending invites for a user.
     */
    public Uni<List<ConversationInvite>> getMyInvites(String userId, String status, int limit, int offset) {
        StringBuilder queryBuilder = new StringBuilder("""
            SELECT ci.*,
                   c.conversationGUID as conv_id, c.title as conv_title,
                   u1.userUUID as inviter_uuid, u1.username as inviter_username,
                   u1.displayName as inviter_display, u1.profilePicture as inviter_pic,
                   u2.userUUID as invitee_uuid, u2.username as invitee_username,
                   u2.displayName as invitee_display, u2.profilePicture as invitee_pic
            FROM conversation_invite ci
            LEFT JOIN conversation c ON ci.conversation_id = c.conversationGUID
            LEFT JOIN user u1 ON ci.inviter_id = u1.userUUID
            LEFT JOIN user u2 ON ci.invitee_id = u2.userUUID
            WHERE ci.invitee_id = ?
            """);

        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (status != null && !status.isEmpty()) {
            queryBuilder.append(" AND ci.status = ?");
            params.add(status.toLowerCase());
        }

        queryBuilder.append(" ORDER BY ci.created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return pool.preparedQuery(queryBuilder.toString())
                .execute(Tuple.from(params))
                .map(rows -> {
                    List<ConversationInvite> invites = new ArrayList<>();
                    for (Row row : rows) {
                        invites.add(mapRowToInvite(row));
                    }
                    return invites;
                });
    }

    /**
     * Get count of pending invites for a user.
     */
    public Uni<Integer> getPendingInviteCount(String userId) {
        String query = """
            SELECT COUNT(*) as count FROM conversation_invite
            WHERE invitee_id = ? AND status = 'pending'
            AND (expires_at IS NULL OR expires_at > NOW())
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(userId))
                .map(rows -> {
                    for (Row row : rows) {
                        return row.getInteger("count");
                    }
                    return 0;
                });
    }

    /**
     * Get invites for a specific conversation.
     */
    public Uni<List<ConversationInvite>> getConversationInvites(String conversationId) {
        String query = """
            SELECT ci.*,
                   c.conversationGUID as conv_id, c.title as conv_title,
                   u1.userUUID as inviter_uuid, u1.username as inviter_username,
                   u1.displayName as inviter_display, u1.profilePicture as inviter_pic,
                   u2.userUUID as invitee_uuid, u2.username as invitee_username,
                   u2.displayName as invitee_display, u2.profilePicture as invitee_pic
            FROM conversation_invite ci
            LEFT JOIN conversation c ON ci.conversation_id = c.conversationGUID
            LEFT JOIN user u1 ON ci.inviter_id = u1.userUUID
            LEFT JOIN user u2 ON ci.invitee_id = u2.userUUID
            WHERE ci.conversation_id = ?
            ORDER BY ci.created_at DESC
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId))
                .map(rows -> {
                    List<ConversationInvite> invites = new ArrayList<>();
                    for (Row row : rows) {
                        invites.add(mapRowToInvite(row));
                    }
                    return invites;
                });
    }

    /**
     * Check if a user can be invited to a conversation.
     * Returns false if the user doesn't exist, is already a participant, or already has a pending invite.
     */
    public Uni<Boolean> canInviteUser(String conversationId, String usernameOrEmail) {
        String query = """
            SELECT u.userUUID,
                   (SELECT COUNT(*) FROM conversation_participant cp
                    WHERE cp.conversation_id = ? AND cp.user_id = u.userUUID AND cp.is_active = TRUE) as is_participant,
                   (SELECT COUNT(*) FROM conversation_invite ci
                    WHERE ci.conversation_id = ? AND ci.invitee_id = u.userUUID AND ci.status = 'pending'
                    AND (ci.expires_at IS NULL OR ci.expires_at > NOW())) as has_pending_invite
            FROM user u
            WHERE u.username = ? OR u.email = ?
            LIMIT 1
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId, conversationId, usernameOrEmail, usernameOrEmail))
                .map(rows -> {
                    for (Row row : rows) {
                        int isParticipant = row.getInteger("is_participant");
                        int hasPendingInvite = row.getInteger("has_pending_invite");
                        return isParticipant == 0 && hasPendingInvite == 0;
                    }
                    return false; // User not found
                });
    }

    /**
     * Resend an invite by resetting its status to pending and updating the timestamp.
     */
    public Uni<ConversationInvite> resendInvite(String inviteId, String userId) {
        return getInviteById(inviteId)
                .chain(invite -> {
                    if (invite == null) {
                        return Uni.createFrom().failure(new RuntimeException("Invite not found"));
                    }
                    if (!invite.getInviterId().toString().equals(userId)) {
                        return Uni.createFrom().failure(new RuntimeException("Only the inviter can resend an invite"));
                    }

                    // The inviter may have been demoted or removed from the
                    // conversation since the invite was created — re-check
                    // their invite permission before re-activating it.
                    return canInvite(invite.getConversationId().toString(), userId)
                            .chain(allowed -> {
                                if (!allowed) {
                                    return Uni.createFrom().failure(new RuntimeException("No longer permitted to invite in this conversation"));
                                }

                                // Do not resurrect terminal invites: a declined or
                                // accepted invite must stay terminal.
                                if (invite.getStatus() == ConversationInvite.InviteStatus.ACCEPTED
                                        || invite.getStatus() == ConversationInvite.InviteStatus.DECLINED) {
                                    return Uni.createFrom().failure(new RuntimeException("Invite has already been " + invite.getStatus().name().toLowerCase()));
                                }

                                String updateQuery = """
                                    UPDATE conversation_invite
                                    SET status = 'pending', responded_at = NULL, created_at = NOW(),
                                        expires_at = DATE_ADD(NOW(), INTERVAL 7 DAY)
                                    WHERE id = ? AND status IN ('pending', 'expired', 'cancelled')
                                    """;

                                return pool.preparedQuery(updateQuery)
                                        .execute(Tuple.of(inviteId))
                                        .chain(v -> getInviteById(inviteId));
                            });
                });
    }

    // ==========================================
    // PARTICIPANT OPERATIONS
    // ==========================================

    /**
     * Add a participant to a conversation.
     */
    public Uni<ConversationParticipant> addParticipant(
            String conversationId,
            String userId,
            String role) {

        String participantId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String effectiveRole = role != null ? role.toLowerCase() : "member";
        boolean canInvite = "admin".equals(effectiveRole) || "owner".equals(effectiveRole);
        boolean canAddCharacters = canInvite;

        String insertQuery = """
            INSERT INTO conversation_participant (
                id, conversation_id, user_id, role, is_active,
                can_invite, can_add_characters, joined_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                is_active = TRUE, left_at = NULL, joined_at = VALUES(joined_at)
            """;

        return pool.preparedQuery(insertQuery)
                .execute(Tuple.tuple()
                        .addValue(participantId)
                        .addValue(conversationId)
                        .addValue(userId)
                        .addValue(effectiveRole)
                        .addValue(true)
                        .addValue(canInvite)
                        .addValue(canAddCharacters)
                        .addValue(now))
                .chain(result -> getParticipant(conversationId, userId))
                .invoke(participant -> {
                    JsonObject event = new JsonObject()
                            .put("conversationId", conversationId)
                            .put("userId", userId)
                            .put("role", effectiveRole);
                    vertx.eventBus().publish("participant.joined." + conversationId, event);
                    log.info("Added participant {} to conversation {} with role {}",
                            userId, conversationId, effectiveRole);
                });
    }

    /**
     * Remove a participant from a conversation.
     */
    public Uni<Boolean> removeParticipant(String conversationId, String userId, String requesterId) {
        // Check permissions
        return canRemoveParticipant(conversationId, userId, requesterId)
                .chain(canRemove -> {
                    if (!canRemove) {
                        return Uni.createFrom().failure(new RuntimeException("You don't have permission to remove this participant"));
                    }

                    String updateQuery = """
                        UPDATE conversation_participant
                        SET is_active = FALSE, left_at = ?
                        WHERE conversation_id = ? AND user_id = ?
                        """;

                    return pool.preparedQuery(updateQuery)
                            .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), conversationId, userId))
                            .map(result -> result.rowCount() > 0)
                            .invoke(removed -> {
                                if (removed) {
                                    JsonObject event = new JsonObject()
                                            .put("conversationId", conversationId)
                                            .put("userId", userId);
                                    vertx.eventBus().publish("participant.left." + conversationId, event);
                                    log.info("Removed participant {} from conversation {}", userId, conversationId);
                                }
                            });
                });
    }

    /**
     * Leave a conversation.
     */
    public Uni<Boolean> leaveConversation(String conversationId, String userId) {
        // Check if user is the owner
        return isOwner(conversationId, userId)
                .chain(isOwner -> {
                    if (isOwner) {
                        return Uni.createFrom().failure(new RuntimeException("Owner cannot leave. Transfer ownership first."));
                    }

                    String updateQuery = """
                        UPDATE conversation_participant
                        SET is_active = FALSE, left_at = ?
                        WHERE conversation_id = ? AND user_id = ?
                        """;

                    return pool.preparedQuery(updateQuery)
                            .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), conversationId, userId))
                            .map(result -> result.rowCount() > 0)
                            .invoke(left -> {
                                if (left) {
                                    JsonObject event = new JsonObject()
                                            .put("conversationId", conversationId)
                                            .put("userId", userId);
                                    vertx.eventBus().publish("participant.left." + conversationId, event);
                                    log.info("User {} left conversation {}", userId, conversationId);
                                }
                            });
                });
    }

    /**
     * Update participant settings.
     */
    public Uni<ConversationParticipant> updateParticipant(
            String conversationId,
            String userId,
            String requesterId,
            String role,
            Boolean canInvite,
            Boolean canAddCharacters,
            String nickname) {

        // Check permissions
        return hasAdminPermission(conversationId, requesterId)
                .chain(hasPermission -> {
                    if (!hasPermission) {
                        return Uni.createFrom().failure(new RuntimeException("You don't have permission to update participants"));
                    }

                    List<String> updates = new ArrayList<>();
                    List<Object> params = new ArrayList<>();

                    if (role != null) {
                        String normalizedRole = role.trim().toLowerCase();
                        // Only the owner can set or remove the 'owner' role. Admins may
                        // assign any other role (member/admin). Prevents admin self-
                        // promotion to owner.
                        if ("owner".equals(normalizedRole)) {
                            return isOwner(conversationId, requesterId)
                                    .chain(isOwner -> {
                                        if (!isOwner) {
                                            return Uni.createFrom().failure(new RuntimeException("Only the owner can assign the owner role"));
                                        }
                                        updates.add("role = ?");
                                        params.add(normalizedRole);
                                        return continueUpdate(conversationId, userId, updates, params);
                                    });
                        }
                        updates.add("role = ?");
                        params.add(normalizedRole);
                    }
                    if (canInvite != null) {
                        updates.add("can_invite = ?");
                        params.add(canInvite);
                    }
                    if (canAddCharacters != null) {
                        updates.add("can_add_characters = ?");
                        params.add(canAddCharacters);
                    }
                    if (nickname != null) {
                        updates.add("nickname = ?");
                        params.add(nickname);
                    }

                    return continueUpdate(conversationId, userId, updates, params);
                });
    }

    /**
     * Continue the participant update after the permission gates. Extracted so the
     * owner-role branch can share the SQL + event-publish tail.
     */
    private Uni<ConversationParticipant> continueUpdate(
            String conversationId,
            String userId,
            List<String> updates,
            List<Object> params) {

        if (updates.isEmpty()) {
            return getParticipant(conversationId, userId);
        }

        params.add(conversationId);
        params.add(userId);

        String updateQuery = "UPDATE conversation_participant SET " +
                String.join(", ", updates) +
                " WHERE conversation_id = ? AND user_id = ?";

        return pool.preparedQuery(updateQuery)
                .execute(Tuple.from(params))
                .chain(result -> getParticipant(conversationId, userId))
                .invoke(participant -> {
                    JsonObject event = new JsonObject()
                            .put("conversationId", conversationId)
                            .put("userId", userId);
                    vertx.eventBus().publish("participant.updated." + conversationId, event);
                });
    }

    /**
     * Transfer ownership.
     * Uses a transaction to ensure atomicity - all changes succeed or none do.
     */
    public Uni<Conversation> transferOwnership(String conversationId, String currentOwnerId, String newOwnerId) {
        return isOwner(conversationId, currentOwnerId)
                .chain(isOwner -> {
                    if (!isOwner) {
                        return Uni.createFrom().failure(new RuntimeException("Only the owner can transfer ownership"));
                    }

                    return isParticipant(conversationId, newOwnerId)
                            .chain(isParticipant -> {
                                if (!isParticipant) {
                                    return Uni.createFrom().failure(new RuntimeException("New owner must be a participant"));
                                }

                                // Use transaction to ensure all updates succeed or fail together
                                return pool.withTransaction(conn -> {
                                    // Update conversation owner with optimistic lock
                                    String updateConvQuery = """
                                        UPDATE conversation SET ownerId = ?
                                        WHERE conversationGUID = ? AND ownerId = ?
                                        """;

                                    return conn.preparedQuery(updateConvQuery)
                                            .execute(Tuple.of(newOwnerId, conversationId, currentOwnerId))
                                            .chain(r1 -> {
                                                if (r1.rowCount() == 0) {
                                                    return Uni.createFrom().failure(new RuntimeException("Ownership has already changed"));
                                                }
                                                // Update old owner to admin
                                                String demoteQuery = """
                                                    UPDATE conversation_participant
                                                    SET role = 'admin'
                                                    WHERE conversation_id = ? AND user_id = ? AND role = 'owner'
                                                    """;
                                                return conn.preparedQuery(demoteQuery)
                                                        .execute(Tuple.of(conversationId, currentOwnerId));
                                            })
                                            .chain(r2 -> {
                                                // Update new owner
                                                String promoteQuery = """
                                                    UPDATE conversation_participant
                                                    SET role = 'owner', can_invite = TRUE, can_add_characters = TRUE
                                                    WHERE conversation_id = ? AND user_id = ?
                                                    """;
                                                return conn.preparedQuery(promoteQuery)
                                                        .execute(Tuple.of(conversationId, newOwnerId));
                                            });
                                })
                                .chain(r3 -> getConversation(conversationId))
                                .invoke(conv -> {
                                    JsonObject event = new JsonObject()
                                            .put("conversationId", conversationId)
                                            .put("previousOwner", currentOwnerId)
                                            .put("newOwner", newOwnerId);
                                    vertx.eventBus().publish("conversation.ownership.transferred." + conversationId, event);
                                    log.info("Transferred ownership of conversation {} from {} to {}",
                                            conversationId, currentOwnerId, newOwnerId);
                                });
                            });
                });
    }

    /**
     * Get participant by conversation and user.
     */
    public Uni<ConversationParticipant> getParticipant(String conversationId, String userId) {
        String query = """
            SELECT cp.*,
                   u.userUUID, u.username, u.displayName, u.profilePicture, u.email
            FROM conversation_participant cp
            JOIN user u ON cp.user_id = u.userUUID
            WHERE cp.conversation_id = ? AND cp.user_id = ?
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId, userId))
                .map(rows -> {
                    for (Row row : rows) {
                        return mapRowToParticipant(row);
                    }
                    return null;
                });
    }

    /**
     * Get all participants for a conversation.
     */
    public Uni<List<ConversationParticipant>> getParticipants(String conversationId) {
        String query = """
            SELECT cp.*,
                   u.userUUID, u.username, u.displayName, u.profilePicture, u.email
            FROM conversation_participant cp
            JOIN user u ON cp.user_id = u.userUUID
            WHERE cp.conversation_id = ? AND cp.is_active = TRUE
            ORDER BY cp.role DESC, cp.joined_at ASC
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId))
                .map(rows -> {
                    List<ConversationParticipant> participants = new ArrayList<>();
                    for (Row row : rows) {
                        participants.add(mapRowToParticipant(row));
                    }
                    return participants;
                });
    }

    /**
     * Mark conversation as read for user.
     */
    public Uni<ConversationParticipant> markAsRead(String conversationId, String userId) {
        String updateQuery = """
            UPDATE conversation_participant
            SET last_read_at = ?
            WHERE conversation_id = ? AND user_id = ?
            """;

        return pool.preparedQuery(updateQuery)
                .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), conversationId, userId))
                .chain(result -> getParticipant(conversationId, userId));
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private Uni<User> findUserByUsernameOrEmail(String usernameOrEmail) {
        String query = """
            SELECT * FROM user
            WHERE username = ? OR email = ?
            LIMIT 1
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(usernameOrEmail, usernameOrEmail))
                .map(rows -> {
                    for (Row row : rows) {
                        return mapRowToUser(row);
                    }
                    return null;
                });
    }

    /**
     * Search users by query string.
     */
    public Uni<List<User>> searchUsers(String query, int limit) {
        String searchQuery = """
            SELECT userUUID, username, displayName, profilePicture, email
            FROM user
            WHERE (username LIKE ? OR displayName LIKE ? OR email LIKE ?)
            AND isBanned = FALSE
            LIMIT ?
            """;

        String pattern = "%" + query + "%";

        return pool.preparedQuery(searchQuery)
                .execute(Tuple.of(pattern, pattern, pattern, limit))
                .map(rows -> {
                    List<User> users = new ArrayList<>();
                    for (Row row : rows) {
                        users.add(mapRowToUser(row));
                    }
                    return users;
                });
    }

    private Uni<Boolean> isParticipant(String conversationId, String userId) {
        String query = """
            SELECT COUNT(*) as count FROM conversation_participant
            WHERE conversation_id = ? AND user_id = ? AND is_active = TRUE
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId, userId))
                .map(rows -> {
                    for (Row row : rows) {
                        return row.getInteger("count") > 0;
                    }
                    return false;
                });
    }

    private Uni<Boolean> hasPendingInvite(String conversationId, String userId) {
        String query = """
            SELECT COUNT(*) as count FROM conversation_invite
            WHERE conversation_id = ? AND invitee_id = ? AND status = 'pending'
            AND (expires_at IS NULL OR expires_at > NOW())
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId, userId))
                .map(rows -> {
                    for (Row row : rows) {
                        return row.getInteger("count") > 0;
                    }
                    return false;
                });
    }

    private Uni<Boolean> canInvite(String conversationId, String userId) {
        String query = """
            SELECT 1 FROM conversation c
            LEFT JOIN conversation_participant cp
                ON c.conversationGUID = cp.conversation_id AND cp.user_id = ?
            WHERE c.conversationGUID = ?
            AND (c.ownerId = ? OR cp.can_invite = TRUE OR cp.role IN ('owner', 'admin'))
            LIMIT 1
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(userId, conversationId, userId))
                .map(rows -> rows.rowCount() > 0);
    }

    private Uni<Boolean> canCancelInvite(ConversationInvite invite, String userId) {
        // Inviter can cancel
        if (invite.getInviterId().toString().equals(userId)) {
            return Uni.createFrom().item(true);
        }
        // Admin can cancel
        return hasAdminPermission(invite.getConversationId().toString(), userId);
    }

    private Uni<Boolean> canRemoveParticipant(String conversationId, String userId, String requesterId) {
        // User can remove themselves (leave)
        if (userId.equals(requesterId)) {
            return isOwner(conversationId, userId).map(isOwner -> !isOwner);
        }
        // Admin can remove members
        return hasAdminPermission(conversationId, requesterId);
    }

    private Uni<Boolean> isOwner(String conversationId, String userId) {
        String query = """
            SELECT 1 FROM conversation WHERE conversationGUID = ? AND ownerId = ?
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId, userId))
                .map(rows -> rows.rowCount() > 0);
    }

    private Uni<Boolean> hasAdminPermission(String conversationId, String userId) {
        String query = """
            SELECT 1 FROM conversation c
            LEFT JOIN conversation_participant cp
                ON c.conversationGUID = cp.conversation_id AND cp.user_id = ?
            WHERE c.conversationGUID = ?
            AND (c.ownerId = ? OR cp.role IN ('owner', 'admin'))
            LIMIT 1
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(userId, conversationId, userId))
                .map(rows -> rows.rowCount() > 0);
    }

    private Uni<Conversation> getConversation(String conversationId) {
        String query = "SELECT * FROM conversation WHERE conversationGUID = ?";

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId))
                .map(rows -> {
                    for (Row row : rows) {
                        return Conversation.builder()
                                .id(UUID.fromString(row.getString("conversationGUID")))
                                .ownerId(UUID.fromString(row.getString("ownerId")))
                                .title(row.getString("title"))
                                .build();
                    }
                    return null;
                });
    }

    // ==========================================
    // ROW MAPPERS
    // ==========================================

    private ConversationInvite mapRowToInvite(Row row) {
        // conversation_id is NOT NULL in the schema, but LEFT JOINs can still
        // surface NULL when a conversation was deleted; guard both nullable
        // columns the same way rather than crashing the mapper.
        String conversationId = row.getString("conversation_id");
        String inviterId = row.getString("inviter_id");
        ConversationInvite invite = ConversationInvite.builder()
                .id(UUID.fromString(row.getString("id")))
                .conversationId(conversationId != null ? UUID.fromString(conversationId) : null)
                .inviterId(inviterId != null ? UUID.fromString(inviterId) : null)
                .inviteeId(row.getString("invitee_id") != null ?
                        UUID.fromString(row.getString("invitee_id")) : null)
                .inviteeEmail(row.getString("invitee_email"))
                .inviteeUsername(row.getString("invitee_username"))
                .status(ConversationInvite.InviteStatus.valueOf(
                        row.getString("status").toUpperCase()))
                .message(row.getString("message"))
                .roleToGrant(ConversationParticipant.ParticipantRole.valueOf(
                        row.getString("role_to_grant").toUpperCase()))
                .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("created_at")))
                .respondedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("responded_at")))
                .expiresAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("expires_at")))
                .build();

        // Set conversation
        if (row.getString("conv_id") != null) {
            Conversation conv = new Conversation();
            conv.setId(UUID.fromString(row.getString("conv_id")));
            conv.setTitle(row.getString("conv_title"));
            invite.setConversation(conv);
        }

        // Set inviter
        if (row.getString("inviter_uuid") != null) {
            User inviter = new User();
            inviter.setId(UUID.fromString(row.getString("inviter_uuid")));
            inviter.setUsername(row.getString("inviter_username"));
            inviter.setDisplayName(row.getString("inviter_display"));
            inviter.setProfilePicture(row.getString("inviter_pic"));
            invite.setInviter(inviter);
        }

        // Set invitee
        if (row.getString("invitee_uuid") != null) {
            User invitee = new User();
            invitee.setId(UUID.fromString(row.getString("invitee_uuid")));
            invitee.setUsername(row.getString("invitee_username"));
            invitee.setDisplayName(row.getString("invitee_display"));
            invitee.setProfilePicture(row.getString("invitee_pic"));
            invite.setInvitee(invitee);
        }

        return invite;
    }

    private ConversationParticipant mapRowToParticipant(Row row) {
        ConversationParticipant participant = ConversationParticipant.builder()
                .id(UUID.fromString(row.getString("id")))
                .conversationId(UUID.fromString(row.getString("conversation_id")))
                .userId(UUID.fromString(row.getString("user_id")))
                .role(ConversationParticipant.ParticipantRole.valueOf(
                        row.getString("role").toUpperCase()))
                .nickname(row.getString("nickname"))
                .isActive(row.getBoolean("is_active"))
                .canInvite(row.getBoolean("can_invite"))
                .canAddCharacters(row.getBoolean("can_add_characters"))
                .joinedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("joined_at")))
                .leftAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("left_at")))
                .lastReadAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("last_read_at")))
                .lastTypingAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("last_typing_at")))
                .build();

        // Set user
        User user = new User();
        user.setId(UUID.fromString(row.getString("userUUID")));
        user.setUsername(row.getString("username"));
        user.setDisplayName(row.getString("displayName"));
        user.setProfilePicture(row.getString("profilePicture"));
        if (row.getColumnIndex("email") >= 0) {
            user.setEmail(row.getString("email"));
        }
        participant.setUser(user);

        return participant;
    }

    private User mapRowToUser(Row row) {
        User user = new User();
        user.setId(UUID.fromString(row.getString("userUUID")));
        user.setUsername(row.getString("username"));
        user.setDisplayName(row.getString("displayName"));
        user.setProfilePicture(row.getString("profilePicture"));
        if (row.getColumnIndex("email") >= 0) {
            user.setEmail(row.getString("email"));
        }
        return user;
    }
}
