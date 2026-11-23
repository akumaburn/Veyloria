package com.eonmux.chatllm.services;

import io.quarkus.runtime.Startup;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.models.ConversationCharacter;
import com.eonmux.chatllm.models.InterjectionRequest;
import io.smallrye.mutiny.Uni;

import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonArray;
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
 * Service for group chat operations.
 * Handles multi-character conversations, turn management, and interjections.
 */
@ApplicationScoped
@Startup
@Slf4j
public class GroupChatService {

    private static final int MAX_CHARACTERS_PER_GROUP = 10;
    private static final int DEFAULT_MAX_CONSECUTIVE_RESPONSES = 3;
    /** A scene opens with a small working group, not the whole roster. */
    public static final int INITIAL_CAST_SIZE = 3;
    private static final int INTERJECTION_EXPIRY_MINUTES = 5;
    private static final long CLEANUP_INTERVAL_MS = 60_000; // Check every minute

    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    OpeningCastService openingCastService;
    @Inject
    com.eonmux.chatllm.map.StageService stageService;
    // EventBus obtained from vertx.eventBus()
    private final Random random = new Random();
    private long cleanupTimerId = -1;

    /**
     * Start periodic cleanup of expired interjection requests.
     * This prevents conversations from getting stuck in INTERJECTION_PENDING state.
     *
     * Triggered by @PostConstruct (the bean is eagerly created via @Startup).
     * Without this, INTERJECTION_PENDING rows accumulate indefinitely.
     */
    @PostConstruct
    void startInterjectionCleanup() {
        cleanupTimerId = vertx.setPeriodic(CLEANUP_INTERVAL_MS, timerId -> {
            cleanupExpiredInterjections()
                .subscribe().with(
                    v -> {},
                    err -> log.error("Failed to cleanup expired interjections", err));
        });
        log.info("Started interjection cleanup timer (interval: {}ms, expiry: {} minutes)",
                CLEANUP_INTERVAL_MS, INTERJECTION_EXPIRY_MINUTES);
    }

    @PreDestroy
    void stopInterjectionCleanup() {
        if (cleanupTimerId >= 0) {
            vertx.cancelTimer(cleanupTimerId);
        }
    }

    /**
     * Cleanup expired interjection requests and reset affected conversation states.
     */
    private Uni<Void> cleanupExpiredInterjections() {
        LocalDateTime expiryThreshold = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(INTERJECTION_EXPIRY_MINUTES);

        // Find and expire old pending interjections
        String expireQuery = """
            UPDATE interjection_request
            SET status = 'expired'
            WHERE status IN ('pending', 'approved') AND (expiresAt < UTC_TIMESTAMP() OR createdAt < ?)
            """;

        return pool.preparedQuery(expireQuery)
            .execute(Tuple.of(expiryThreshold))
            .chain(result -> {
                int expired = result.rowCount();
                if (expired > 0) {
                    log.info("Expired {} stale interjection requests", expired);
                }

                String idleCondition = """
                    c.groupState = 'INTERJECTION_PENDING' AND NOT EXISTS (
                        SELECT 1 FROM interjection_request ir
                        WHERE ir.conversationId = c.conversationGUID
                        AND ir.status IN ('pending', 'approved')
                    )
                    """;
                return pool.preparedQuery("SELECT c.conversationGUID FROM conversation c WHERE " + idleCondition)
                        .execute(Tuple.tuple())
                        .chain(rows -> {
                            List<Uni<Void>> resets = new ArrayList<>();
                            for (Row row : rows) {
                                String conversationId = row.getString("conversationGUID");
                                // Recheck pending requests in the UPDATE: a new request
                                // may have arrived since the candidate list was read.
                                resets.add(pool.preparedQuery("""
                                        UPDATE conversation c SET c.groupState = 'WAITING_FOR_USER'
                                        WHERE c.conversationGUID = ? AND
                                        """ + idleCondition)
                                        .execute(Tuple.of(conversationId))
                                        .invoke(reset -> {
                                            if (reset.rowCount() > 0) vertx.eventBus().publish(
                                                    "group:" + conversationId + ":state-changed", new JsonObject()
                                                            .put("conversationId", conversationId)
                                                            .put("groupState", "WAITING_FOR_USER"));
                                        }).replaceWithVoid());
                            }
                            return resets.isEmpty() ? Uni.createFrom().voidItem()
                                    : Uni.join().all(resets).andCollectFailures().replaceWithVoid();
                        });
            })
            .replaceWithVoid();
    }

    /**
     * Create a new group conversation with multiple characters.
     */
    public Uni<Conversation> createGroupConversation(
            String userId,
            String title,
            List<String> characterIds,
            Map<String, Double> characterWeights,
            String turnMode,
            Integer maxConsecutiveResponses,
            String category,
            List<String> tags) {

        if (characterIds == null || characterIds.size() < 2) {
            return Uni.createFrom().failure(new RuntimeException("Group conversation requires at least 2 characters"));
        }

        if (characterIds.size() > MAX_CHARACTERS_PER_GROUP) {
            return Uni.createFrom().failure(new RuntimeException("Maximum " + MAX_CHARACTERS_PER_GROUP + " characters allowed per group"));
        }

        String conversationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Auto-generate title if not provided
        String finalTitle = (title == null || title.trim().isEmpty())
                ? "Group Chat " + now.toLocalDate()
                : title.length() > 255 ? title.substring(0, 255) : title;

        String effectiveTurnMode = turnMode != null ? turnMode.toUpperCase() : "RANDOM";

        // Validate turnMode against allowed values
        Set<String> validTurnModes = Set.of("RANDOM", "ROUND_ROBIN", "WEIGHTED");
        if (!validTurnModes.contains(effectiveTurnMode)) {
            return Uni.createFrom().failure(new RuntimeException("Invalid turn mode: " + turnMode + ". Must be one of: RANDOM, ROUND_ROBIN, WEIGHTED"));
        }

        // Validate maxConsecutiveResponses > 0 to prevent infinite loops in character selection
        int effectiveMaxConsecutive = maxConsecutiveResponses != null && maxConsecutiveResponses > 0
                ? maxConsecutiveResponses : DEFAULT_MAX_CONSECUTIVE_RESPONSES;

        // Verify all characters exist and user has access
        return verifyCharactersAccess(userId, characterIds)
                .chain(hasAccess -> {
                    if (!hasAccess) {
                        return Uni.createFrom().failure(new RuntimeException("One or more characters not found or access denied"));
                    }

                    // Use transaction to ensure atomicity of conversation + character creation
                    String tagsJson = tags != null ? new JsonArray(tags).encode() : null;
                    final String finalTurnMode = effectiveTurnMode;
                    final int finalMaxConsecutive = effectiveMaxConsecutive;

                    return pool.withTransaction(client -> {
                        // Insert conversation with group chat fields
                        String insertQuery = """
                            INSERT INTO conversation (
                                conversationGUID, ownerId, title, createdAt, lastModified,
                                active, isGroupChat, turnMode, maxConsecutiveResponses,
                                consecutiveResponseCount, groupState, category, tags
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """;

                        return client.preparedQuery(insertQuery)
                                .execute(Tuple.tuple()
                                        .addValue(conversationId)
                                        .addValue(userId)
                                        .addValue(finalTitle)
                                        .addValue(now)
                                        .addValue(now)
                                        .addValue(true)
                                        .addValue(true)
                                        .addValue(finalTurnMode)
                                        .addValue(finalMaxConsecutive)
                                        .addValue(0)
                                        .addValue("WAITING_FOR_USER")
                                        .addValue(category)
                                        .addValue(tagsJson))
                                .chain(result -> {
                                    // Add all characters to the group with their weights (within same transaction)
                                    List<Uni<Void>> addFutures = new ArrayList<>();
                                    for (int i = 0; i < characterIds.size(); i++) {
                                        String charId = characterIds.get(i);
                                        Double weight = characterWeights != null ? characterWeights.get(charId) : null;
                                        double effectiveWeight = weight != null ? Math.max(weight, 0.01) : 1.0;
                                        String ccId = UUID.randomUUID().toString();

                                        String ccInsertQuery = """
                                            INSERT INTO conversation_character (
                                                id, conversationId, characterId, joinedAt, isActive,
                                                turnOrder, responseCount, weight, interjectionProbability,
                                                isIntroduced, introducedAt
                                            ) VALUES (?, ?, ?, ?, true, ?, 0, ?, 0.1, ?, ?)
                                            """;

                                        // Provisional cast. OpeningCastService replaces it
                                        // with the group that fits the scenario once the
                                        // transaction commits; this remains the fallback.
                                        boolean introduced = i < INITIAL_CAST_SIZE;
                                        addFutures.add(client.preparedQuery(ccInsertQuery)
                                                .execute(Tuple.tuple()
                                                        .addValue(ccId).addValue(conversationId).addValue(charId)
                                                        .addValue(now).addValue(i).addValue(effectiveWeight)
                                                        .addValue(introduced).addValue(introduced ? now : null))
                                                .replaceWithVoid());
                                    }
                                    return Uni.join().all(addFutures).andCollectFailures();
                                });
                    });
                })
                .chain(cf -> openingCastService.selectOpeningCast(
                        conversationId, userId,
                        openingScenario(finalTitle, category, tags), INITIAL_CAST_SIZE))
                .chain(v -> {
                    // Fetch and return the created conversation
                    return getGroupConversation(conversationId, userId);
                })
                .chain(conversation -> {
                    // Publish creation event
                    publishGroupEvent("group_created", new JsonObject()
                            .put("conversationId", conversationId)
                            .put("userId", userId)
                            .put("characterCount", characterIds.size()));

                    return Uni.createFrom().item(conversation);
                });
    }

    private static String openingScenario(String title, String category, List<String> tags) {
        StringBuilder scenario = new StringBuilder(title == null ? "" : title);
        if (category != null && !category.isBlank()) scenario.append(' ').append(category);
        if (tags != null && !tags.isEmpty()) scenario.append(' ').append(String.join(" ", tags));
        return scenario.toString();
    }

    /**
     * Add a character to an existing group conversation.
     */
    public Uni<ConversationCharacter> addCharacterToGroup(
            String conversationId,
            String characterId,
            String userId,
            Integer turnOrder,
            Double weight) {

        // Verify conversation is a group chat and user owns it
        return verifyGroupConversationOwnership(conversationId, userId)
                .chain(isOwned -> {
                    if (!isOwned) {
                        return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                    }
                    return verifyCharacterAccess(userId, characterId);
                })
                .chain(hasAccess -> {
                    if (!hasAccess) {
                        return Uni.createFrom().failure(new RuntimeException("Character not found or access denied"));
                    }
                    return getCharacterCount(conversationId);
                })
                .chain(count -> {
                    if (count >= MAX_CHARACTERS_PER_GROUP) {
                        return Uni.createFrom().failure(new RuntimeException("Maximum " + MAX_CHARACTERS_PER_GROUP + " characters allowed"));
                    }

                    int order = turnOrder != null ? turnOrder : count;
                    return addCharacterToGroupInternal(conversationId, characterId, order, weight);
                })
                .chain(cc -> {
                    // Publish event. Payload shape matches the characterJoined
                    // subscription resolver — it reads id / conversationId /
                    // characterId / turnOrder off message.body() directly.
                    publishGroupEvent("character_joined", new JsonObject()
                            .put("id", cc.getId() != null ? cc.getId().toString() : UUID.randomUUID().toString())
                            .put("conversationId", conversationId)
                            .put("characterId", characterId)
                            .put("turnOrder", cc.getTurnOrder() != null ? cc.getTurnOrder() : 0));

                    return Uni.createFrom().item(cc);
                });
    }

    /**
     * Remove a character from a group conversation.
     * Clears currentSpeakerId/lastSpeakerId if they match the removed character.
     */
    public Uni<Boolean> removeCharacterFromGroup(String conversationId, String characterId, String userId) {
        return verifyGroupConversationOwnership(conversationId, userId)
                .chain(isOwned -> {
                    if (!isOwned) {
                        return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                    }

                    // Check remaining character count
                    return getCharacterCount(conversationId);
                })
                .chain(count -> {
                    if (count <= 2) {
                        return Uni.createFrom().failure(new RuntimeException("Cannot remove character: group must have at least 2 characters"));
                    }

                    String deleteQuery = """
                        DELETE FROM conversation_character
                        WHERE conversationId = ? AND characterId = ?
                        """;

                    return pool.preparedQuery(deleteQuery)
                            .execute(Tuple.of(conversationId, characterId));
                })
                .chain(result -> {
                    if (result.rowCount() == 0) {
                        return Uni.createFrom().failure(new RuntimeException("Character not found in group"));
                    }

                    // Clear currentSpeakerId/lastSpeakerId if they reference the removed character
                    // Also reset state to WAITING_FOR_USER if the current speaker was removed mid-response
                    return pool.preparedQuery("""
                        UPDATE conversation
                        SET currentSpeakerId = CASE WHEN currentSpeakerId = ? THEN NULL ELSE currentSpeakerId END,
                            lastSpeakerId = CASE WHEN lastSpeakerId = ? THEN NULL ELSE lastSpeakerId END,
                            groupState = CASE WHEN currentSpeakerId = ? THEN 'WAITING_FOR_USER' ELSE groupState END
                        WHERE conversationGUID = ?
                        """)
                        .execute(Tuple.of(characterId, characterId, characterId, conversationId));
                })
                .chain(result -> {
                    // Publish event
                    publishGroupEvent("character_left", new JsonObject()
                            .put("conversationId", conversationId)
                            .put("characterId", characterId));

                    return Uni.createFrom().item(true);
                });
    }

    /**
     * Get the next character to respond based on turn mode.
     */
    public Uni<Character> selectNextCharacter(String conversationId) {
        return getGroupConversation(conversationId, null)
                .chain(conversation -> {
                    if (conversation.getCharacters() == null || conversation.getCharacters().isEmpty()) {
                        return Uni.createFrom().failure(new RuntimeException("No characters in group"));
                    }

                    // Check for pending interjections first
                    return getPendingInterjections(conversationId)
                            .chain(interjections -> {
                                if (!interjections.isEmpty()) {
                                    // Get highest priority interjection
                                    InterjectionRequest approved = interjections.stream()
                                            .filter(ir -> "approved".equals(ir.getStatus()))
                                            .min(InterjectionRequest::comparePriority)
                                            .orElse(null);

                                    if (approved != null && approved.getRequesterId() != null) {
                                        return pool.preparedQuery("""
                                                UPDATE interjection_request SET status = 'consumed'
                                                WHERE id = ? AND status = 'approved'
                                                  AND (expiresAt IS NULL OR expiresAt > UTC_TIMESTAMP())
                                                """)
                                                .execute(Tuple.of(approved.getId().toString()))
                                                .chain(result -> result.rowCount() == 1
                                                        ? verifyCharacterInGroup(conversationId, approved.getRequesterId().toString())
                                                            .chain(active -> active
                                                                    ? ensureCharacterIntroduced(conversationId,
                                                                            approved.getRequesterId().toString())
                                                                        .chain(ignored -> getCharacter(
                                                                                approved.getRequesterId().toString()))
                                                                    : selectNextCharacter(conversationId))
                                                        : selectNextCharacter(conversationId));
                                    }
                                }

                                // Normal turn selection
                                return selectCharacterByTurnMode(conversation);
                            });
                });
    }

    /**
     * Starts one character turn for a user message or a manual continue.
     *
     * Returns the speaker that was given the turn, or null when the
     * conversation was busy. The claim, the speaker commit and the response
     * event are one sequence, so two triggers cannot both speak.
     */
    public Uni<Character> startCharacterTurn(String conversationId, String userId) {
        return claimTurn(conversationId, userId, false, true, false, null);
    }

    /**
     * Starts a turn for one named character, used when a message is
     * regenerated: the caller re-runs that character's own reply, so turn
     * order does not apply. The character must still belong to the
     * conversation and the conversation must be free.
     */
    public Uni<Character> startCharacterTurnFor(String conversationId, String characterId, String userId) {
        return claimTurn(conversationId, userId, false, true, false, characterId);
    }

    /**
     * Gives one character the next turn after the party arrives somewhere.
     *
     * Travel and journeys run in the background, so the caller has no reply to
     * wait for. The speaker must be committed before the response event goes
     * out: {@code LLMBridge.verifyCharacterTurn} rejects a character whose id
     * is not the stored current speaker. Every exit path here either releases
     * the claim or leaves a speaker in place, so a conversation cannot get stuck
     * in CHARACTER_RESPONDING with nobody speaking.
     */
    public Uni<Void> triggerArrivalResponse(String conversationId, String userId) {
        return claimTurn(conversationId, userId, true, false, false, null).replaceWithVoid();
    }

    /**
     * Claims the floor, picks a speaker, commits that speaker, then sends the
     * response event.
     *
     * The claim statement is the only place a turn begins. It flips the state,
     * clears the speaker and, when asked, resets the per-message budget in one
     * row update, so a trigger that loses the race changes nothing. Both
     * free states are claimable: a user message must never wait behind a
     * pending interjection request, because that request expires on its own and
     * the message would sit unanswered until it does.
     */
    private Uni<Character> claimTurn(String conversationId, String userId, boolean autoTriggered,
                                     boolean resetBudget, boolean requireApprovedInterjection,
                                     String forcedCharacterId) {
        String budgetReset = resetBudget ? ", consecutiveResponseCount = 0" : "";
        String approval = requireApprovedInterjection ? """
                  AND EXISTS (SELECT 1 FROM interjection_request ir
                    WHERE ir.conversationId = conversation.conversationGUID
                      AND ir.status = 'approved' AND ir.requesterType = 'character'
                      AND (ir.expiresAt IS NULL OR ir.expiresAt > UTC_TIMESTAMP()))
                """ : "";

        String claimQuery = """
                UPDATE conversation
                SET groupState = 'CHARACTER_RESPONDING', currentSpeakerId = NULL"""
                + budgetReset
                + """

                WHERE conversationGUID = ?
                  AND groupState IN ('WAITING_FOR_USER', 'INTERJECTION_PENDING')
                """
                + approval;

        Uni<Character> speaker = forcedCharacterId == null
                ? selectNextCharacter(conversationId)
                : verifyCharacterInGroup(conversationId, forcedCharacterId)
                    .chain(member -> member
                            ? getCharacter(forcedCharacterId)
                            : Uni.createFrom().failure(new RuntimeException("Character not in this group")));

        return pool.preparedQuery(claimQuery)
                .execute(Tuple.of(conversationId))
                .chain(claim -> claim.rowCount() == 0
                        ? Uni.createFrom().item((Character) null)
                        : speaker
                            .chain(character -> pool.preparedQuery("""
                                    UPDATE conversation SET currentSpeakerId = ?
                                    WHERE conversationGUID = ? AND groupState = 'CHARACTER_RESPONDING'
                                      AND currentSpeakerId IS NULL
                                    """).execute(Tuple.of(character.getId().toString(), conversationId))
                                    .map(claimed -> claimed.rowCount() == 0 ? null : character)))
                .invoke(character -> {
                    if (character == null) return;
                    vertx.eventBus().send("group.character.respond", new JsonObject()
                            .put("conversationId", conversationId)
                            .put("characterId", character.getId().toString())
                            .put("characterName", character.getName())
                            .put("systemPrompt", character.getFullSystemPrompt())
                            .put("userId", userId)
                            .put("autoTriggered", autoTriggered));
                    publishGroupEvent("turn_changed", new JsonObject()
                            .put("conversationId", conversationId)
                            .put("characterId", character.getId().toString())
                            .put("characterName", character.getName())
                            .put("characterAvatar", character.getAvatar()));
                })
                .onFailure().call(error -> {
                    log.warn("Could not start a character turn for conversation {}: {}",
                            conversationId, error.getMessage());
                    return releaseTurn(conversationId);
                })
                .onFailure().recoverWithItem((Character) null);
    }

    /**
     * Releases a claimed turn nobody can speak for. The client needs the
     * state-changed event too, otherwise it keeps the composer disabled.
     */
    private Uni<Void> releaseTurn(String conversationId) {
        return pool.preparedQuery("""
                UPDATE conversation SET groupState = 'WAITING_FOR_USER', currentSpeakerId = NULL
                WHERE conversationGUID = ? AND groupState = 'CHARACTER_RESPONDING'
                """)
                .execute(Tuple.of(conversationId))
                .invoke(released -> {
                    if (released.rowCount() > 0) {
                        vertx.eventBus().publish("group:" + conversationId + ":state-changed", new JsonObject()
                                .put("conversationId", conversationId)
                                .put("groupState", "WAITING_FOR_USER"));
                    }
                })
                .replaceWithVoid();
    }

    /**
     * Brings a roster member onstage when the player names them.
     *
     * A user message used to add the next name in turn order as well. That
     * made every message introduce a stranger, and the newcomer spoke before
     * the people already in the scene. A cast should stay stable until the
     * player calls someone in or the casting agent proposes them.
     */
    public Uni<String> revealAddressedCastMember(String conversationId) {
        return latestPlayerTurn(conversationId)
                .onFailure().recoverWithItem((PlayerTurn) null)
                .chain(turn -> {
                    String named = turn == null ? null : turn.addresseeId();
                    return named == null
                            ? Uni.createFrom().nullItem()
                            : introduceOffstage(conversationId, named);
                })
                .invoke(name -> {
                    if (name == null) return;
                    publishGroupEvent("character_introduced", new JsonObject()
                            .put("conversationId", conversationId)
                            .put("characterName", name));
                    log.info("Introduced {} into group conversation {}", name, conversationId);
                });
    }

    private Uni<String> introduceOffstage(String conversationId, String preferredId) {
        return pool.preparedQuery("""
                SELECT cc.characterId, c.name
                FROM conversation_character cc
                JOIN `character` c ON cc.characterId = c.characterUUID
                WHERE cc.conversationId = ? AND cc.isActive = TRUE
                  AND (cc.isIntroduced = FALSE OR cc.isIntroduced IS NULL)
                  AND (? IS NULL OR cc.characterId = ?)
                ORDER BY cc.turnOrder, cc.characterId
                LIMIT 1
                """)
                .execute(Tuple.of(conversationId, preferredId, preferredId))
                .chain(rows -> {
                    if (rows.size() == 0) return Uni.createFrom().nullItem();
                    Row row = rows.iterator().next();
                    String characterId = row.getString("characterId");
                    String name = row.getString("name");
                    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                    return pool.preparedQuery("""
                            UPDATE conversation_character
                            SET isIntroduced = TRUE, introducedAt = ?
                            WHERE conversationId = ? AND characterId = ?
                              AND (isIntroduced = FALSE OR isIntroduced IS NULL)
                            """)
                            .execute(Tuple.of(now, conversationId, characterId))
                            .chain(updated -> updated.rowCount() > 0
                                    ? stageService.bringCharacterToStage(conversationId, characterId)
                                            .replaceWith(name)
                                    : Uni.createFrom().nullItem());
                });
    }

    /**
     * Puts a specifically requested member onstage. An approved interjection is
     * an explicit request from the player, so it can be that member's entrance.
     */
    private Uni<Void> ensureCharacterIntroduced(String conversationId, String characterId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return pool.preparedQuery("""
                UPDATE conversation_character
                SET isIntroduced = TRUE, introducedAt = COALESCE(introducedAt, ?)
                WHERE conversationId = ? AND characterId = ?
                  AND (isIntroduced = FALSE OR isIntroduced IS NULL)
                """)
                .execute(Tuple.of(now, conversationId, characterId))
                .chain(updated -> stageService.bringCharacterToStage(conversationId, characterId))
                .replaceWithVoid();
    }

    /**
     * Select character based on conversation's turn mode.
     *
     * The player's addressee speaks first. Turn mode is a fallback, not a
     * substitute for reading the message: with round robin, a player who
     * answered one character's question could be answered by the next person
     * in the queue while the addressee never got a turn at all.
     */
    private Uni<Character> selectCharacterByTurnMode(Conversation conversation) {
        List<ConversationCharacter> activeChars = conversation.getCharacters().stream()
                .filter(cc -> cc.getIsActive())
                .filter(cc -> cc.getIsIntroduced() == null || cc.getIsIntroduced())
                .collect(Collectors.toList());

        if (activeChars.isEmpty()) {
            return Uni.createFrom().failure(new RuntimeException("No active characters available"));
        }

        // The addressee gets the first reply only. Once a character has
        // answered, rotation takes over. The budget counter cannot answer this:
        // a manual continue resets it to zero, which would hand the same
        // character the floor again.
        Uni<String> addressee = conversation.getId() == null
                ? Uni.createFrom().nullItem()
                : latestPlayerTurn(conversation.getId().toString())
                        .map(turn -> turn == null || turn.answered() ? null : turn.addresseeId())
                        .onFailure().recoverWithItem((String) null);

        return addressee.map(addresseeId -> {
            ConversationCharacter selected = addresseeId == null ? null
                    : activeChars.stream()
                            .filter(cc -> cc.getCharacterId() != null
                                    && addresseeId.equals(cc.getCharacterId().toString()))
                            .findFirst().orElse(null);

            // A member who just entered the scene speaks before the established
            // cast, so an entrance lands as a beat instead of waiting behind a
            // rotating queue. The opening three are ordered the same way.
            if (selected == null) selected = firstNewcomer(activeChars);

            String turnMode = conversation.getTurnMode();
            // Default to RANDOM if turnMode is null
            String effectiveTurnMode = (turnMode != null) ? turnMode.toUpperCase() : "RANDOM";

            if (selected == null) {
                switch (effectiveTurnMode) {
                    case "ROUND_ROBIN":
                        selected = selectRoundRobin(activeChars, conversation.getLastSpeakerId());
                        break;
                    case "WEIGHTED":
                        selected = selectWeighted(eligibleCharacters(activeChars, conversation.getLastSpeakerId(),
                                conversation.getConsecutiveResponseCount(), conversation.getMaxConsecutiveResponses()));
                        break;
                    case "RANDOM":
                    default:
                        selected = selectRandom(activeChars, conversation.getLastSpeakerId(),
                                conversation.getConsecutiveResponseCount(),
                                conversation.getMaxConsecutiveResponses());
                        break;
                }
            }

            if (selected == null || selected.getCharacterId() == null) {
                throw new RuntimeException("Failed to select character");
            }
            return selected;
        }).chain(selected -> getCharacter(selected.getCharacterId().toString()));
    }

    /**
     * The player's most recent message, with the character it was addressed to.
     *
     * The backend picks the speaker before any model sees the prompt, so the
     * direction of a message has to be read here. Two signals count:
     *
     * A name for one active cast member. Only a name unique to one member in
     * this scene is an address; a shared surname names a family, not a person.
     *
     * An answer to the previous character's question. When the player answers
     * a question rather than opening a new topic, the asker is the addressee
     * even without a name.
     *
     * Everything else is addressed to the group.
     */
    public Uni<PlayerTurn> latestPlayerTurn(String conversationId) {
        return pool.preparedQuery("""
                SELECT seq, content, displayName, isBase64Encoded
                FROM message
                WHERE conversationId = ? AND role = 'user' AND isDeleted = FALSE
                ORDER BY seq DESC
                LIMIT 1
                """).execute(Tuple.of(conversationId))
                .chain(rows -> {
                    if (rows.size() == 0) return Uni.createFrom().nullItem();
                    Row row = rows.iterator().next();
                    String content = messageContent(row);
                    if (content == null || content.isBlank()
                            || content.stripLeading().startsWith("[SESSION_START]")) {
                        return Uni.createFrom().nullItem();
                    }
                    String displayName = row.getString("displayName");
                    long userSeq = row.getLong("seq");
                    return rosterForAddressing(conversationId).chain(cast ->
                            repliesAfter(conversationId, userSeq).chain(repliers -> {
                                boolean answered = !repliers.isEmpty();
                                String byName = matchAddressee(content, cast);
                                if (byName != null) {
                                    return Uni.createFrom().item(addressedTurn(
                                            content, displayName, byName, cast, answered, repliers));
                                }
                                return lastCharacterBefore(conversationId, userSeq)
                                        .map(last -> last != null && endsWithQuestion(last.content())
                                                ? addressedTurn(content, displayName,
                                                        last.characterId(), cast, answered, repliers)
                                                : new PlayerTurn(content, displayName,
                                                        null, null, answered, repliers));
                            }));
                });
    }

    /**
     * A player message and the character it names or answers.
     *
     * {@code answered} is true once a character has replied since the player
     * sent it. {@code repliers} names those characters in speaking order, so a
     * later speaker can be told the message already has an answer instead of
     * being invited to answer it again. The addressee speaks first and only
     * first.
     */
    public record PlayerTurn(String content, String displayName,
                             String addresseeId, String addresseeName, boolean answered,
                             List<String> repliers) {
        public PlayerTurn(String content, String displayName,
                          String addresseeId, String addresseeName, boolean answered) {
            this(content, displayName, addresseeId, addresseeName, answered, List.of());
        }

        public PlayerTurn(String content, String displayName,
                          String addresseeId, String addresseeName) {
            this(content, displayName, addresseeId, addresseeName, false, List.of());
        }
    }

    /** A roster member considered when resolving the player's addressee. */
    record CastMember(String id, String name) {
    }

    private record LastLine(String characterId, String content) {
    }

    private static final Set<String> NAME_SUFFIXES = Set.of("jr", "sr", "ii", "iii", "iv", "v");

    private static PlayerTurn addressedTurn(String content, String displayName,
                                            String addresseeId, List<CastMember> cast,
                                            boolean answered, List<String> repliers) {
        String name = cast.stream()
                .filter(member -> member.id().equals(addresseeId))
                .map(CastMember::name)
                .findFirst().orElse(null);
        // Someone who is no longer in the group is not a valid addressee.
        return name == null ? new PlayerTurn(content, displayName, null, null, answered, repliers)
                : new PlayerTurn(content, displayName, addresseeId, name, answered, repliers);
    }

    private Uni<List<CastMember>> rosterForAddressing(String conversationId) {
        return pool.preparedQuery("""
                SELECT c.characterUUID, c.name
                FROM conversation_character cc
                JOIN `character` c ON cc.characterId = c.characterUUID
                WHERE cc.conversationId = ? AND cc.isActive = TRUE
                ORDER BY cc.turnOrder, cc.characterId
                """).execute(Tuple.of(conversationId))
                .map(rows -> {
                    List<CastMember> cast = new ArrayList<>();
                    for (Row row : rows) {
                        String name = row.getString("name");
                        if (name != null && !name.isBlank()) {
                            cast.add(new CastMember(row.getString("characterUUID"), name));
                        }
                    }
                    return cast;
                });
    }

    /**
     * The characters who have spoken since the player's message.
     *
     * The prompt uses their names to tell a later speaker that the message
     * already has an answer. A count alone cannot say whether the addressee or
     * someone else answered.
     */
    private Uni<List<String>> repliesAfter(String conversationId, long seq) {
        return pool.preparedQuery("""
                SELECT displayName
                FROM message
                WHERE conversationId = ? AND role = 'assistant' AND isDeleted = FALSE AND seq > ?
                ORDER BY seq
                """).execute(Tuple.of(conversationId, seq))
                .map(rows -> {
                    Set<String> names = new LinkedHashSet<>();
                    for (Row row : rows) {
                        String name = row.getString("displayName");
                        names.add(name == null || name.isBlank() ? "a character" : name);
                    }
                    return new ArrayList<>(names);
                });
    }

    private Uni<LastLine> lastCharacterBefore(String conversationId, long seq) {
        return pool.preparedQuery("""
                SELECT characterId, content, isBase64Encoded
                FROM message
                WHERE conversationId = ? AND role = 'assistant' AND isDeleted = FALSE AND seq < ?
                ORDER BY seq DESC
                LIMIT 1
                """).execute(Tuple.of(conversationId, seq))
                .map(rows -> {
                    if (rows.size() == 0) return null;
                    Row row = rows.iterator().next();
                    return new LastLine(row.getString("characterId"), messageContent(row));
                });
    }

    /**
     * The character a message names, or null when it names none or names
     * several. A shared name part is not an address: in a scene with a father
     * and son called Kaelen, "Kaelen" alone does not pick one of them.
     */
    static String matchAddressee(String message, List<CastMember> cast) {
        if (message == null || message.isBlank() || cast == null || cast.isEmpty()) return null;
        List<String> words = nameWords(message);
        if (words.isEmpty()) return null;

        Map<String, Integer> holders = new HashMap<>();
        for (CastMember member : cast) {
            for (String token : new HashSet<>(nameWords(member.name()))) {
                holders.merge(token, 1, Integer::sum);
            }
        }

        String bestId = null;
        int bestScore = 0;
        boolean tied = false;
        for (CastMember member : cast) {
            List<String> name = nameWords(member.name());
            int score = 0;
            if (name.size() > 1 && containsSequence(words, name)) {
                // A full name beats any single part, so "Kaelen Vane Jr."
                // picks the son even though the father's name is a prefix.
                score = 1000 + String.join("", name).length();
            }
            // "Kaelen Jr." is a real way to name the son. First name plus
            // suffix is a longer, more specific form than the bare first name,
            // but still short of the full name.
            if (name.size() > 2 && NAME_SUFFIXES.contains(name.get(name.size() - 1))) {
                List<String> shortForm = List.of(name.get(0), name.get(name.size() - 1));
                if (containsSequence(words, shortForm)) {
                    score = Math.max(score, 900 + String.join("", shortForm).length());
                }
            }
            for (String part : name) {
                if (part.length() < 3 || NAME_SUFFIXES.contains(part)) continue;
                if (holders.getOrDefault(part, 0) != 1) continue;
                if (!words.contains(part)) continue;
                score = Math.max(score, 100 + part.length());
            }
            if (score == 0) continue;
            if (score > bestScore) {
                bestScore = score;
                bestId = member.id();
                tied = false;
            } else if (score == bestScore) {
                tied = true;
            }
        }
        return tied ? null : bestId;
    }

    /**
     * True when the last spoken line in a turn is a question. Only dialogue
     * counts; a question mark inside an action beat is not addressed to anyone.
     */
    static boolean endsWithQuestion(String content) {
        if (content == null || content.isBlank()) return false;
        java.util.regex.Matcher quoted = java.util.regex.Pattern
                .compile("\"([^\"]*)\"").matcher(content);
        String speech = null;
        while (quoted.find()) speech = quoted.group(1);
        String tail = speech != null ? speech : content.replaceAll("\\*[^*]*\\*", " ");
        return tail.stripTrailing().endsWith("?");
    }

    private static List<String> nameWords(String value) {
        List<String> words = new ArrayList<>();
        if (value == null) return words;
        for (String word : value.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")) {
            if (word.isBlank()) continue;
            // "Jinx's plan" names Jinx. Strip the possessive so the name
            // still matches while a shared word like "bringing" does not.
            if (word.endsWith("'s") && word.length() > 2) {
                word = word.substring(0, word.length() - 2);
            }
            words.add(word);
        }
        return words;
    }

    private static boolean containsSequence(List<String> words, List<String> sequence) {
        if (sequence.isEmpty() || sequence.size() > words.size()) return false;
        for (int i = 0; i <= words.size() - sequence.size(); i++) {
            if (words.subList(i, i + sequence.size()).equals(sequence)) return true;
        }
        return false;
    }

    private static String messageContent(Row row) {
        String content = row.getString("content");
        if (Boolean.TRUE.equals(row.getBoolean("isBase64Encoded")) && content != null) {
            try {
                return new String(Base64.getDecoder().decode(content),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return content;
            }
        }
        return content;
    }

    /**
     * Returns the earliest member who entered the scene but has not spoken yet.
     * Legacy rows have no entry time and keep their previous turn order.
     */
    private ConversationCharacter firstNewcomer(List<ConversationCharacter> chars) {
        return chars.stream()
                .filter(cc -> cc.getIntroducedAt() != null)
                .filter(cc -> cc.getResponseCount() == null || cc.getResponseCount() == 0)
                .min(Comparator
                        .comparingInt((ConversationCharacter cc) ->
                                cc.getTurnOrder() != null ? cc.getTurnOrder() : 0)
                        .thenComparing(cc -> cc.getCharacterId() != null
                                ? cc.getCharacterId().toString() : ""))
                .orElse(null);
    }

    /**
     * Random selection with consecutive response limit.
     */
    private ConversationCharacter selectRandom(
            List<ConversationCharacter> chars,
            UUID lastSpeakerId,
            Integer consecutiveCount,
            Integer maxConsecutive) {

        List<ConversationCharacter> candidates = eligibleCharacters(chars, lastSpeakerId, consecutiveCount, maxConsecutive);
        return candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * Avoids an immediate repeat. The per-user-message turn budget is enforced
     * by the response chain, not by this speaker filter.
     */
    private List<ConversationCharacter> eligibleCharacters(List<ConversationCharacter> chars, UUID lastSpeakerId,
                                                          Integer consecutiveCount, Integer maxConsecutive) {
        if (lastSpeakerId == null) return chars;
        List<ConversationCharacter> candidates = chars.stream()
                .filter(cc -> !lastSpeakerId.equals(cc.getCharacterId()))
                .collect(Collectors.toList());
        return candidates.isEmpty() ? chars : candidates;
    }

    /**
     * Round robin selection.
     */
    private ConversationCharacter selectRoundRobin(List<ConversationCharacter> chars, UUID lastSpeakerId) {
        // Sort by turn order, then by characterId for deterministic ordering when turnOrder is equal
        // This ensures consistent behavior across restarts and concurrent requests
        chars.sort(Comparator
            .comparingInt((ConversationCharacter cc) -> cc.getTurnOrder() != null ? cc.getTurnOrder() : 0)
            .thenComparing(cc -> cc.getCharacterId() != null ? cc.getCharacterId().toString() : ""));

        if (lastSpeakerId == null) {
            return chars.get(0);
        }

        // Find current position and get next
        for (int i = 0; i < chars.size(); i++) {
            UUID charId = chars.get(i).getCharacterId();
            if (charId != null && charId.equals(lastSpeakerId)) {
                return chars.get((i + 1) % chars.size());
            }
        }

        return chars.get(0);
    }

    /**
     * Weighted random selection.
     * Weights must be positive - negative/zero weights are treated as minimum weight (0.01).
     */
    private ConversationCharacter selectWeighted(List<ConversationCharacter> chars) {
        // Helper to get effective weight (minimum 0.01 to avoid zero/negative weights)
        java.util.function.ToDoubleFunction<ConversationCharacter> getEffectiveWeight = cc -> {
            double weight = cc.getWeight() != null ? cc.getWeight() : 1.0;
            return Math.max(weight, 0.01); // Ensure positive weight
        };

        double totalWeight = chars.stream()
                .mapToDouble(getEffectiveWeight)
                .sum();

        if (totalWeight <= 0) {
            // Fallback if somehow all weights are zero (shouldn't happen with Math.max)
            return chars.get(random.nextInt(chars.size()));
        }

        double r = random.nextDouble() * totalWeight;
        double cumulative = 0;

        for (ConversationCharacter cc : chars) {
            cumulative += getEffectiveWeight.applyAsDouble(cc);
            if (r <= cumulative) {
                return cc;
            }
        }

        return chars.get(chars.size() - 1);
    }

    // NOTE: Character response recording and state updates are handled by
    // LLMVerticle.updateGroupChatStateAfterResponse() which also chains to the next character.
    // Do not add a separate recordCharacterResponse method here to avoid duplicate updates.

    /**
     * Request an interjection.
     * Note: Interjections are not allowed when conversation is PAUSED.
     */
    public Uni<InterjectionRequest> requestInterjection(
            String conversationId,
            String characterId,
            String userId,
            String reason,
            Integer priority) {

        String requesterId = characterId; // null for user interjection
        String requesterType = characterId != null ? "character" : "user";
        int effectivePriority = priority != null ? priority : 0;

        // Check conversation state - reject if PAUSED. Also verify the caller
        // is the owner or an active participant — previously any authenticated
        // user could create a pending interjection row in any conversation.
        return pool.preparedQuery("""
                SELECT c.groupState
                FROM conversation c
                LEFT JOIN conversation_participant cp
                    ON c.conversationGUID = cp.conversation_id AND cp.user_id = ? AND cp.is_active = true
                WHERE c.conversationGUID = ?
                """)
                .execute(Tuple.of(userId, conversationId))
                .chain(rows -> {
                    if (rows.size() == 0) {
                        return Uni.createFrom().failure(new RuntimeException("Conversation not found"));
                    }
                    var row = rows.iterator().next();
                    String state = row.getString("groupState");
                    if ("PAUSED".equals(state)) {
                        return Uni.createFrom().failure(new RuntimeException("Cannot request interjection while conversation is paused"));
                    }
                    return Uni.createFrom().item(true);
                })
                .chain(v -> {
                    // Verify the caller owns or actively participates in the
                    // conversation before allowing the insert.
                    return pool.preparedQuery("""
                            SELECT COUNT(*) as count
                            FROM conversation c
                            LEFT JOIN conversation_participant cp
                                ON c.conversationGUID = cp.conversation_id AND cp.user_id = ? AND cp.is_active = true
                            WHERE c.conversationGUID = ?
                              AND (c.ownerId = ? OR cp.user_id = ?)
                            """)
                            .execute(Tuple.of(userId, conversationId, userId, userId))
                            .map(rows -> rows.iterator().next().getLong("count") > 0);
                })
                .chain(hasAccess -> {
                    if (!hasAccess) {
                        return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                    }
                    // Verify character if provided
                    if (characterId != null) {
                        return verifyCharacterInGroup(conversationId, characterId);
                    }
                    return Uni.createFrom().item(true);
                })
                .chain(valid -> {
                    if (!valid) {
                        return Uni.createFrom().failure(new RuntimeException("Character not in this group"));
                    }

                    String id = UUID.randomUUID().toString();
                    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

                    String insertQuery = """
                        INSERT INTO interjection_request (
                            id, conversationId, requesterId, requesterType,
                            reason, priority, status, createdAt, expiresAt
                        ) VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)
                        """;

                    return pool.preparedQuery(insertQuery)
                            .execute(Tuple.tuple()
                                    .addValue(id).addValue(conversationId).addValue(requesterId)
                                    .addValue(requesterType).addValue(reason).addValue(effectivePriority)
                                    .addValue(now).addValue(now.plusMinutes(INTERJECTION_EXPIRY_MINUTES)))
                            .map(result -> {
                                InterjectionRequest ir = new InterjectionRequest();
                                ir.setId(UUID.fromString(id));
                                ir.setConversationId(UUID.fromString(conversationId));
                                ir.setRequesterId(requesterId != null ? UUID.fromString(requesterId) : null);
                                ir.setRequesterType(requesterType);
                                ir.setReason(reason);
                                ir.setPriority(effectivePriority);
                                ir.setStatus("pending");
                                ir.setCreatedAt(now.toString());
                                return ir;
                            });
                })
                .chain(ir -> {
                    // Queue an idle conversation without revoking an in-flight
                    // character turn. The flag is informational: a user message
                    // still claims the floor, and the send retires the request.
                    return pool.preparedQuery("""
                        UPDATE conversation SET groupState = 'INTERJECTION_PENDING'
                        WHERE conversationGUID = ? AND groupState = 'WAITING_FOR_USER'
                        """)
                            .execute(Tuple.of(conversationId))
                            .map(v -> ir);
                })
                .chain(ir -> {
                    // Publish event with the full InterjectionRequest shape so
                    // the interjectionRequested subscription consumer can read
                    // id / requesterId / reason / priority / status without nulls.
                    publishGroupEvent("interjection_requested", interjectionRequestToJson(ir));
                    return Uni.createFrom().item(ir);
                });
    }

    /**
     * Approve an interjection request.
     * The interjector will speak next, so set state appropriately.
     * Approvals while paused are queued until resume.
     */
    public Uni<InterjectionRequest> approveInterjection(String interjectionId, String userId) {
        return updateInterjectionStatus(interjectionId, "approved", userId)
                .call(ir -> {
                    if (ir.isCharacterRequest()) {
                        // An in-flight character keeps its turn. The approved request
                        // is consumed by selection after that response finishes.
                        return startApprovedInterjection(ir.getConversationId().toString(), userId);
                    }
                    return pool.preparedQuery("""
                            UPDATE conversation SET groupState = 'WAITING_FOR_USER', currentSpeakerId = NULL
                            WHERE conversationGUID = ? AND groupState != 'PAUSED'
                            """)
                            .execute(Tuple.of(ir.getConversationId().toString()))
                            .invoke(result -> {
                                if (result.rowCount() > 0) vertx.eventBus().publish("group.conversation.paused",
                                        new JsonObject().put("conversationId", ir.getConversationId().toString()));
                            }).replaceWithVoid();
                })
                .invoke(ir -> publishGroupEvent("interjection_approved", interjectionRequestToJson(ir)));
    }

    private Uni<Void> startApprovedInterjection(String conversationId, String userId) {
        // The claim requires a live approved character request, so an approval
        // that lands during another character's turn changes nothing and is
        // consumed by the next selection instead.
        return claimTurn(conversationId, userId, true, false, true, null).replaceWithVoid();
    }

    /**
     * Reject an interjection request.
     * Reset state to WAITING_FOR_USER if no more pending interjections (unless PAUSED).
     */
    public Uni<InterjectionRequest> rejectInterjection(String interjectionId, String userId) {
        return updateInterjectionStatus(interjectionId, "rejected", userId)
                .chain(ir -> {
                    // Check if there are any remaining pending interjections
                    String conversationId = ir.getConversationId().toString();
                    return getPendingInterjections(conversationId)
                        .chain(remaining -> {
                            if (remaining.isEmpty()) {
                                // No more pending - reset to WAITING_FOR_USER only if currently INTERJECTION_PENDING
                                // Don't reset if PAUSED or CHARACTER_RESPONDING (would interrupt in-flight response)
                                return pool.preparedQuery("""
                                    UPDATE conversation SET groupState = 'WAITING_FOR_USER'
                                    WHERE conversationGUID = ? AND groupState = 'INTERJECTION_PENDING'
                                    """)
                                    .execute(Tuple.of(conversationId))
                                    .map(v -> ir);
                            }
                            return Uni.createFrom().item(ir);
                        });
                })
                .chain(ir -> {
                    publishGroupEvent("interjection_rejected", interjectionRequestToJson(ir));
                    return Uni.createFrom().item(ir);
                });
    }

    /**
     * Cancel an interjection request.
     * Only the conversation owner or user interjection requester can cancel.
     * Reset state to WAITING_FOR_USER if no more pending interjections.
     */
    public Uni<Boolean> cancelInterjection(String interjectionId, String userId) {
        // First, get the conversationId before deleting
        return pool.preparedQuery("SELECT conversationId FROM interjection_request WHERE id = ?")
                .execute(Tuple.of(interjectionId))
                .chain(rows -> {
                    if (rows.size() == 0) {
                        return Uni.createFrom().item(false);
                    }
                    String conversationId = rows.iterator().next().getString("conversationId");

                    // Verify ownership and delete. The previous clause
                    // `(ir.requesterType = 'user' AND ir.requesterId IS NULL)`
                    // never referenced the caller, so ANY user could cancel
                    // another user's pending interjection. User interjections
                    // intentionally store requesterId = NULL (it doubles as the
                    // speaker's character id), so the requester's identity is
                    // not recoverable from the row — the safe boundary is
                    // conversation owner OR active participant.
                    return pool.preparedQuery("""
                        DELETE ir FROM interjection_request ir
                        INNER JOIN conversation c ON ir.conversationId = c.conversationGUID
                        LEFT JOIN conversation_participant cp
                            ON c.conversationGUID = cp.conversation_id AND cp.user_id = ? AND cp.is_active = true
                        WHERE ir.id = ? AND ir.status = 'pending'
                          AND (c.ownerId = ? OR cp.user_id = ?)
                        """)
                        .execute(Tuple.of(userId, interjectionId, userId, userId))
                        .chain(result -> {
                            if (result.rowCount() == 0) {
                                return Uni.createFrom().item(false);
                            }

                            // Check if there are any remaining pending interjections
                            return getPendingInterjections(conversationId)
                                .chain(remaining -> {
                                    if (remaining.isEmpty()) {
                                        // No more pending - reset to WAITING_FOR_USER
                                        return pool.preparedQuery("""
                                            UPDATE conversation SET groupState = 'WAITING_FOR_USER'
                                            WHERE conversationGUID = ? AND groupState = 'INTERJECTION_PENDING'
                                            """)
                                            .execute(Tuple.of(conversationId))
                                            .map(v -> true);
                                    }
                                    return Uni.createFrom().item(true);
                                });
                        });
                });
    }

    /**
     * Pause a group conversation.
     */
    public Uni<Conversation> pauseGroupConversation(String conversationId, String userId) {
        return updateGroupState(conversationId, userId, "PAUSED");
    }

    /**
     * Resume a group conversation.
     */
    public Uni<Conversation> resumeGroupConversation(String conversationId, String userId) {
        return updateGroupState(conversationId, userId, "WAITING_FOR_USER")
                .call(conv -> startApprovedInterjection(conversationId, userId))
                .chain(conv -> getGroupConversation(conversationId, userId));
    }

    /**
     * Update group conversation settings.
     */
    public Uni<Conversation> updateGroupSettings(
            String conversationId,
            String userId,
            String turnMode,
            Integer maxConsecutiveResponses) {

        return verifyGroupConversationOwnership(conversationId, userId)
                .chain(isOwned -> {
                    if (!isOwned) {
                        return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                    }

                    List<String> updates = new ArrayList<>();
                    List<Object> params = new ArrayList<>();

                    if (turnMode != null) {
                        updates.add("turnMode = ?");
                        params.add(turnMode.toUpperCase());
                    }
                    if (maxConsecutiveResponses != null && maxConsecutiveResponses > 0) {
                        // Only update if valid (> 0) to prevent infinite loops in character selection
                        updates.add("maxConsecutiveResponses = ?");
                        params.add(maxConsecutiveResponses);
                    }

                    if (updates.isEmpty()) {
                        return getGroupConversation(conversationId, userId);
                    }

                    LocalDateTime updateTime = LocalDateTime.now(ZoneOffset.UTC);
                    updates.add("lastModified = ?");
                    params.add(updateTime);
                    updates.add("updatedAt = ?");
                    params.add(updateTime);
                    params.add(conversationId);

                    String updateQuery = "UPDATE conversation SET " +
                            String.join(", ", updates) +
                            " WHERE conversationGUID = ?";

                    return pool.preparedQuery(updateQuery)
                            .execute(Tuple.from(params))
                            .chain(result -> getGroupConversation(conversationId, userId));
                });
    }

    // ========== Helper Methods ==========

    private Uni<ConversationCharacter> addCharacterToGroupInternal(
            String conversationId, String characterId, int turnOrder) {
        return addCharacterToGroupInternal(conversationId, characterId, turnOrder, 1.0);
    }

    private Uni<ConversationCharacter> addCharacterToGroupInternal(
            String conversationId, String characterId, int turnOrder, Double weight) {

        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Validate weight - must be positive, default to 1.0, minimum 0.01
        double effectiveWeight = weight != null ? Math.max(weight, 0.01) : 1.0;

        String insertQuery = """
            INSERT INTO conversation_character (
                id, conversationId, characterId, joinedAt, isActive,
                turnOrder, responseCount, weight, interjectionProbability,
                isIntroduced, introducedAt
            ) VALUES (?, ?, ?, ?, true, ?, 0, ?, 0.1, TRUE, ?)
            """;

        return pool.preparedQuery(insertQuery)
                .execute(Tuple.tuple()
                        .addValue(id).addValue(conversationId).addValue(characterId)
                        .addValue(now).addValue(turnOrder).addValue(effectiveWeight).addValue(now))
                .chain(result -> {
                    // Fetch the complete ConversationCharacter with Character data
                    String fetchQuery = """
                        SELECT cc.*,
                               ch.characterUUID as char_id, ch.name as char_name, ch.description as char_description,
                               ch.systemPrompt as char_systemPrompt, ch.greeting as char_greeting, ch.avatar as char_avatar,
                               ch.personality as char_personality, ch.gender as char_gender,
                               ch.pronouns as char_pronouns, ch.ageYears as char_ageYears,
                               ch.isPublic as char_isPublic, ch.ownerId as char_ownerId
                        FROM conversation_character cc
                        JOIN `character` ch ON cc.characterId = ch.characterUUID
                        WHERE cc.id = ?
                        """;
                    return pool.preparedQuery(fetchQuery)
                            .execute(Tuple.of(id));
                })
                .map(rows -> {
                    if (rows.size() > 0) {
                        return mapRowToConversationCharacterWithCharacter(rows.iterator().next());
                    }
                    // Fallback: create basic ConversationCharacter without full character data
                    ConversationCharacter cc = new ConversationCharacter();
                    cc.setId(UUID.fromString(id));
                    cc.setConversationId(UUID.fromString(conversationId));
                    cc.setCharacterId(UUID.fromString(characterId));
                    cc.setJoinedAt(now.toString());
                    cc.setIsActive(true);
                    cc.setTurnOrder(turnOrder);
                    cc.setResponseCount(0);
                    cc.setWeight(effectiveWeight);
                    cc.setInterjectionProbability(0.1);
                    return cc;
                });
    }

    private Uni<Conversation> getGroupConversation(String conversationId, String userId) {
        String query = """
            SELECT c.*,
                   (SELECT COUNT(*) FROM message WHERE conversationId = c.conversationGUID) as messageCount
            FROM conversation c
            WHERE c.conversationGUID = ?
            """ + (userId != null ? " AND c.ownerId = ?" : "");

        Tuple params = userId != null ? Tuple.of(conversationId, userId) : Tuple.of(conversationId);

        return pool.preparedQuery(query)
                .execute(params)
                .chain(rows -> {
                    if (rows.size() == 0) {
                        return Uni.createFrom().failure(new RuntimeException("Conversation not found"));
                    }

                    Row row = rows.iterator().next();
                    Conversation conv = mapRowToConversation(row);

                    // Load characters
                    return getConversationCharacters(conversationId)
                            .map(chars -> {
                                conv.setCharacters(chars);
                                return conv;
                            });
                });
    }

    private Uni<List<ConversationCharacter>> getConversationCharacters(String conversationId) {
        String query = """
            SELECT cc.*,
                   ch.characterUUID as char_id, ch.name as char_name, ch.description as char_description,
                   ch.systemPrompt as char_systemPrompt, ch.greeting as char_greeting, ch.avatar as char_avatar,
                   ch.personality as char_personality, ch.gender as char_gender,
                   ch.pronouns as char_pronouns, ch.ageYears as char_ageYears,
                   ch.isPublic as char_isPublic, ch.ownerId as char_ownerId
            FROM conversation_character cc
            JOIN `character` ch ON cc.characterId = ch.characterUUID
            WHERE cc.conversationId = ? AND cc.isActive = true
            ORDER BY cc.turnOrder, cc.characterId
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId))
                .map(rows -> {
                    List<ConversationCharacter> result = new ArrayList<>();
                    rows.forEach(row -> result.add(mapRowToConversationCharacterWithCharacter(row)));
                    return result;
                });
    }

    private Uni<List<InterjectionRequest>> getPendingInterjections(String conversationId) {
        // Filter out expired interjections (expiresAt is in the past)
        String query = """
            SELECT * FROM interjection_request
            WHERE conversationId = ? AND status IN ('pending', 'approved')
              AND (expiresAt IS NULL OR expiresAt > UTC_TIMESTAMP())
            ORDER BY priority DESC, createdAt ASC
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId))
                .map(rows -> {
                    List<InterjectionRequest> result = new ArrayList<>();
                    rows.forEach(row -> result.add(mapRowToInterjectionRequest(row)));
                    return result;
                });
    }

    private Uni<Character> getCharacter(String characterId) {
        String query = """
            SELECT * FROM `character` WHERE characterUUID = ?
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(characterId))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return null;
                    }
                    Row row = rows.iterator().next();
                    Character ch = new Character();
                    ch.setId(UUID.fromString(row.getString("characterUUID")));
                    ch.setName(row.getString("name"));
                    ch.setDescription(row.getString("description"));
                    ch.setSystemPrompt(row.getString("systemPrompt"));
                    ch.setGreeting(row.getString("greeting"));
                    ch.setAvatar(row.getString("avatar"));
                    ch.setPersonality(row.getString("personality"));
                    ch.setAppearance(row.getString("appearance"));
                    ch.setGender(row.getString("gender"));
                    ch.setPronouns(row.getString("pronouns"));
                    ch.setAgeYears(row.getInteger("ageYears"));
                    ch.setHeight(row.getString("height"));
                    ch.setBuild(row.getString("build"));
                    ch.setHair(row.getString("hair"));
                    ch.setEyes(row.getString("eyes"));
                    ch.setStrength(row.getInteger("strength"));
                    ch.setDexterity(row.getInteger("dexterity"));
                    ch.setConstitution(row.getInteger("constitution"));
                    ch.setIntelligence(row.getInteger("intelligence"));
                    ch.setWisdom(row.getInteger("wisdom"));
                    ch.setCharisma(row.getInteger("charisma"));
                    ch.setLikes(row.getString("likes"));
                    ch.setDislikes(row.getString("dislikes"));
                    ch.setBackstory(row.getString("backstory"));
                    ch.setGoals(row.getString("goals"));
                    ch.setExamples(row.getString("examples"));
                    return ch;
                });
    }

    private Uni<Boolean> verifyCharactersAccess(String userId, List<String> characterIds) {
        if (characterIds.isEmpty()) {
            return Uni.createFrom().item(true);
        }

        String placeholders = String.join(",", Collections.nCopies(characterIds.size(), "?"));
        String query = """
            SELECT COUNT(*) as count FROM `character`
            WHERE characterUUID IN (%s)
                AND (ownerId = ? OR isPublic = true)
            """.formatted(placeholders);

        List<Object> params = new ArrayList<>(characterIds);
        params.add(userId);

        return pool.preparedQuery(query)
                .execute(Tuple.from(params))
                .map(rows -> {
                    long count = rows.iterator().next().getLong("count");
                    return count == characterIds.size();
                });
    }

    private Uni<Boolean> verifyCharacterAccess(String userId, String characterId) {
        return verifyCharactersAccess(userId, Collections.singletonList(characterId));
    }

    private Uni<Boolean> verifyGroupConversationOwnership(String conversationId, String userId) {
        String query = """
            SELECT COUNT(*) as count FROM conversation
            WHERE conversationGUID = ? AND ownerId = ? AND isGroupChat = true
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId, userId))
                .map(rows -> rows.iterator().next().getLong("count") > 0);
    }

    private Uni<Boolean> verifyCharacterInGroup(String conversationId, String characterId) {
        String query = """
            SELECT COUNT(*) as count FROM conversation_character
            WHERE conversationId = ? AND characterId = ? AND isActive = true
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId, characterId))
                .map(rows -> rows.iterator().next().getLong("count") > 0);
    }

    private Uni<Integer> getCharacterCount(String conversationId) {
        String query = """
            SELECT COUNT(*) as count FROM conversation_character
            WHERE conversationId = ? AND isActive = true
            """;

        return pool.preparedQuery(query)
                .execute(Tuple.of(conversationId))
                .map(rows -> rows.iterator().next().getInteger("count"));
    }

    private Uni<InterjectionRequest> updateInterjectionStatus(String interjectionId, String status, String userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Only the conversation owner can approve/reject interjections
        return pool.preparedQuery("""
            UPDATE interjection_request ir
            INNER JOIN conversation c ON ir.conversationId = c.conversationGUID
            SET ir.status = ?, ir.resolvedAt = ?
            WHERE ir.id = ? AND c.ownerId = ? AND ir.status = 'pending'
              AND (ir.expiresAt IS NULL OR ir.expiresAt > UTC_TIMESTAMP())
              AND ir.createdAt >= UTC_TIMESTAMP() - INTERVAL 5 MINUTE
            """)
                .execute(Tuple.of(status, now, interjectionId, userId))
                .chain(result -> {
                    if (result.rowCount() == 0) {
                        return Uni.createFrom().failure(new RuntimeException("Interjection not found or access denied"));
                    }

                    return pool.preparedQuery("SELECT * FROM interjection_request WHERE id = ?")
                            .execute(Tuple.of(interjectionId))
                            .map(rows -> mapRowToInterjectionRequest(rows.iterator().next()));
                });
    }

    private Uni<Conversation> updateGroupState(String conversationId, String userId, String newState) {
        return verifyGroupConversationOwnership(conversationId, userId)
                .chain(isOwned -> {
                    if (!isOwned) {
                        return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                    }

                    LocalDateTime stateChangeTime = LocalDateTime.now(ZoneOffset.UTC);
                    return pool.preparedQuery("""
                        UPDATE conversation SET groupState = ?, currentSpeakerId = NULL, lastModified = ?, updatedAt = ?
                        WHERE conversationGUID = ?
                          AND (? != 'WAITING_FOR_USER' OR groupState = 'PAUSED')
                        """)
                            .execute(Tuple.of(newState, stateChangeTime, stateChangeTime, conversationId, newState))
                            .chain(result -> result.rowCount() > 0 ? Uni.createFrom().item(result)
                                    : Uni.createFrom().failure(new IllegalStateException("Conversation is not paused")))
                            .invoke(result -> {
                                if ("PAUSED".equals(newState)) vertx.eventBus().publish("group.conversation.paused",
                                        new JsonObject().put("conversationId", conversationId));
                            });
                })
                .chain(result -> getGroupConversation(conversationId, userId))
                .chain(conv -> {
                    // Publish state change event so frontend can update
                    JsonObject stateEvent = new JsonObject()
                            .put("conversationId", conversationId)
                            .put("groupState", newState)
                            .put("turnMode", conv.getTurnMode())
                            .put("consecutiveResponseCount", conv.getConsecutiveResponseCount() != null ? conv.getConsecutiveResponseCount() : 0);
                    vertx.eventBus().publish("group:" + conversationId + ":state-changed", stateEvent);
                    return Uni.createFrom().item(conv);
                });
    }

    private Conversation mapRowToConversation(Row row) {
        Conversation conv = new Conversation();
        conv.setId(UUID.fromString(row.getString("conversationGUID")));
        conv.setOwnerId(UUID.fromString(row.getString("ownerId")));
        conv.setTitle(row.getString("title"));
        conv.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        conv.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastModified")));
        conv.setLastModified(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastModified")));
        conv.setActive(row.getBoolean("active"));
        conv.setIsGroupChat(row.getBoolean("isGroupChat"));
        // Normalize enum values to UPPER_CASE for GraphQL compatibility
        String turnMode = row.getString("turnMode");
        conv.setTurnMode(turnMode != null ? turnMode.toUpperCase() : null);
        String groupState = row.getString("groupState");
        conv.setGroupState(groupState != null ? groupState.toUpperCase() : null);
        conv.setMaxConsecutiveResponses(row.getInteger("maxConsecutiveResponses"));
        conv.setConsecutiveResponseCount(row.getInteger("consecutiveResponseCount"));

        String currentSpeaker = row.getString("currentSpeakerId");
        if (currentSpeaker != null) {
            conv.setCurrentSpeakerId(UUID.fromString(currentSpeaker));
        }

        String lastSpeaker = row.getString("lastSpeakerId");
        if (lastSpeaker != null) {
            conv.setLastSpeakerId(UUID.fromString(lastSpeaker));
        }

        if (row.getInteger("messageCount") != null) {
            conv.setMessageCount(row.getInteger("messageCount"));
        }

        // Roleplay fields
        conv.setIsRoleplay(row.getBoolean("isRoleplay"));
        String storyTemplateId = row.getString("storyTemplateId");
        if (storyTemplateId != null) {
            conv.setStoryTemplateId(storyTemplateId);
        }
        String activeSceneId = row.getString("activeSceneId");
        if (activeSceneId != null) {
            conv.setActiveSceneId(UUID.fromString(activeSceneId));
        }
        conv.setRpMode(row.getString("rpMode"));
        conv.setAgentPipelineEnabled(row.getBoolean("agentPipelineEnabled"));

        return conv;
    }

    private ConversationCharacter mapRowToConversationCharacter(Row row) {
        ConversationCharacter cc = new ConversationCharacter();
        cc.setId(UUID.fromString(row.getString("id")));
        cc.setConversationId(UUID.fromString(row.getString("conversationId")));
        cc.setCharacterId(UUID.fromString(row.getString("characterId")));
        cc.setJoinedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("joinedAt")));
        cc.setIsActive(row.getBoolean("isActive"));
        cc.setTurnOrder(row.getInteger("turnOrder"));
        cc.setResponseCount(row.getInteger("responseCount"));
        cc.setLastResponseAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastResponseAt")));
        cc.setWeight(row.getDouble("weight"));
        cc.setInterjectionProbability(row.getDouble("interjectionProbability"));
        Boolean introduced = row.getBoolean("isIntroduced");
        cc.setIsIntroduced(introduced == null || introduced);
        cc.setIntroducedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("introducedAt")));
        return cc;
    }

    private ConversationCharacter mapRowToConversationCharacterWithCharacter(Row row) {
        ConversationCharacter cc = mapRowToConversationCharacter(row);

        // Populate the Character object from joined data
        Character character = new Character();
        character.setId(UUID.fromString(row.getString("char_id")));
        character.setName(row.getString("char_name"));
        character.setDescription(row.getString("char_description"));
        character.setSystemPrompt(row.getString("char_systemPrompt"));
        character.setGreeting(row.getString("char_greeting"));
        character.setAvatar(row.getString("char_avatar"));
        character.setPersonality(row.getString("char_personality"));
        character.setGender(row.getString("char_gender"));
        character.setPronouns(row.getString("char_pronouns"));
        character.setAgeYears(row.getInteger("char_ageYears"));
        character.setIsPublic(row.getBoolean("char_isPublic"));
        String ownerId = row.getString("char_ownerId");
        if (ownerId != null) {
            character.setOwnerId(UUID.fromString(ownerId));
        }
        cc.setCharacter(character);

        return cc;
    }

    private InterjectionRequest mapRowToInterjectionRequest(Row row) {
        InterjectionRequest ir = new InterjectionRequest();
        ir.setId(UUID.fromString(row.getString("id")));
        ir.setConversationId(UUID.fromString(row.getString("conversationId")));

        String requesterId = row.getString("requesterId");
        if (requesterId != null) {
            ir.setRequesterId(UUID.fromString(requesterId));
        }

        ir.setRequesterType(row.getString("requesterType"));
        ir.setReason(row.getString("reason"));
        ir.setPriority(row.getInteger("priority"));
        ir.setStatus(row.getString("status"));
        ir.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        ir.setResolvedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("resolvedAt")));
        return ir;
    }

    /**
     * Serialize an InterjectionRequest into the flat JSON shape the
     * interjectionRequested / interjectionResolved subscription consumers
     * expect (id, conversationId, requesterId, requesterType, reason,
     * priority, status, createdAt, resolvedAt).
     */
    private JsonObject interjectionRequestToJson(InterjectionRequest ir) {
        JsonObject json = new JsonObject()
                .put("id", ir.getId() != null ? ir.getId().toString() : null)
                .put("conversationId", ir.getConversationId() != null ? ir.getConversationId().toString() : null)
                .put("requesterType", ir.getRequesterType())
                .put("reason", ir.getReason())
                .put("priority", ir.getPriority())
                .put("status", ir.getStatus());
        if (ir.getRequesterId() != null) {
            json.put("requesterId", ir.getRequesterId().toString());
        }
        if (ir.getCreatedAt() != null) {
            json.put("createdAt", ir.getCreatedAt().toString());
        }
        if (ir.getResolvedAt() != null) {
            json.put("resolvedAt", ir.getResolvedAt().toString());
        }
        return json;
    }

    private void publishGroupEvent(String eventType, JsonObject data) {
        String conversationId = data.getString("conversationId");

        // Generic channels carry the wrapped envelope so event-log / audit
        // consumers can see the type and timestamp.
        JsonObject envelope = new JsonObject()
                .put("type", eventType)
                .put("timestamp", LocalDateTime.now(ZoneOffset.UTC).toString())
                .put("data", data);
        vertx.eventBus().publish("group:events", envelope);
        vertx.eventBus().publish("group:" + conversationId, envelope);

        // Per-suffix subscription channels receive a FLAT payload. The
        // SubscriptionResolver consumers read fields directly off
        // message.body() (e.g. data.getString("id")) and would see nulls if
        // we published the wrapped envelope instead.
        String channelSuffix = switch (eventType) {
            case "character_joined" -> "character-joined";
            case "character_left" -> "character-left";
            case "interjection_requested" -> "interjection-requested";
            case "interjection_approved", "interjection_rejected" -> "interjection-resolved";
            case "turn_changed" -> "turn-changed";
            default -> null;
        };

        if (channelSuffix != null && conversationId != null) {
            vertx.eventBus().publish("group:" + conversationId + ":" + channelSuffix, data);
        }

        log.debug("Published group event: {} for conversation: {}",
                eventType, conversationId);
    }
}
