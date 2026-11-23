package com.eonmux.chatllm.services;

import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.InterjectionRequest;
import com.eonmux.chatllm.map.StageService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.*;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static com.eonmux.chatllm.testing.SqlTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupChatServiceTest {
    private final String chat = UUID.randomUUID().toString();
    private final String owner = UUID.randomUUID().toString();
    private final String first = UUID.randomUUID().toString();
    private final String second = UUID.randomUUID().toString();
    private final String request = UUID.randomUUID().toString();
    private Vertx vertx;
    private GroupChatService service;
    private String state;
    private String speaker;
    private String requestStatus;
    private String mode;
    private String lastSpeaker;
    private final Map<String, Boolean> introduced = new HashMap<>();
    private final Map<String, Integer> responseCounts = new HashMap<>();
    private final Map<String, LocalDateTime> entryTimes = new HashMap<>();
    private LocalDateTime expiresAt;
    private LocalDateTime requestedAt;

    @BeforeEach void setup() {
        vertx = Vertx.vertx();
        service = new GroupChatService();
        service.vertx = vertx;
        service.pool = pool(this::execute);
        service.stageService = org.mockito.Mockito.mock(StageService.class);
        org.mockito.Mockito.when(service.stageService.bringCharacterToStage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Uni.createFrom().voidItem());
        state = "WAITING_FOR_USER";
        requestStatus = "pending";
        mode = "ROUND_ROBIN";
        lastSpeaker = first;
    }

    @AfterEach void close() { vertx.closeAndAwait(); }

    @Test void weightedSelectionEnforcesTheConsecutiveLimitEvenForAnOverwhelmingWeight() {
        mode = "WEIGHTED";
        requestStatus = "rejected";
        Character selected = service.selectNextCharacter(chat).await().indefinitely();
        assertEquals(second, selected.getId().toString());
    }

    @Test void skipsRosterMembersWhoHaveNotEnteredTheStory() {
        introduced.put(first, false);
        Character selected = service.selectNextCharacter(chat).await().indefinitely();
        assertEquals(second, selected.getId().toString());
    }

    @Test void aNewlyIntroducedMemberSpeaksBeforeTheEstablishedCast() {
        entryTimes.put(second, LocalDateTime.now());
        responseCounts.put(second, 0);
        Character selected = service.selectNextCharacter(chat).await().indefinitely();
        assertEquals(second, selected.getId().toString());
    }

    @Test void revealsTheOffstageMemberThePlayerNames() {
        service.pool = pool((sql, params) -> {
            if (sql.contains("SELECT seq, content, displayName")) {
                return rows(row(Map.of("seq", 5L, "content", "First, take point.",
                        "displayName", "Amir", "isBase64Encoded", false)));
            }
            if (sql.contains("SELECT c.characterUUID, c.name")) {
                return rows(row(Map.of("characterUUID", first, "name", "First")),
                        row(Map.of("characterUUID", second, "name", "Second")));
            }
            if (sql.contains("SELECT displayName") && sql.contains("role = 'assistant'")) {
                return rows();
            }
            if (sql.contains("SELECT cc.characterId, c.name")) {
                return rows(row(Map.of("characterId", first, "name", "First")));
            }
            if (sql.contains("UPDATE conversation_character") && sql.contains("isIntroduced = TRUE")) {
                return affected(1);
            }
            throw new AssertionError("Unexpected query: " + sql);
        });
        assertEquals("First", service.revealAddressedCastMember(chat).await().indefinitely());
    }

    @Test void anOrdinaryMessageDoesNotAddAStranger() {
        service.pool = pool((sql, params) -> {
            if (sql.contains("SELECT seq, content, displayName")) {
                return rows(row(Map.of("seq", 5L, "content", "We move at dawn.",
                        "displayName", "Amir", "isBase64Encoded", false)));
            }
            if (sql.contains("SELECT c.characterUUID, c.name")) {
                return rows(row(Map.of("characterUUID", first, "name", "First")),
                        row(Map.of("characterUUID", second, "name", "Second")));
            }
            if (sql.contains("SELECT displayName") && sql.contains("role = 'assistant'")) {
                return rows();
            }
            if (sql.contains("seq < ?")) {
                return rows();
            }
            throw new AssertionError("No introduction query is allowed: " + sql);
        });
        assertNull(service.revealAddressedCastMember(chat).await().indefinitely());
    }

    @Test void idleApprovalStartsGenerationWithTheOwnersSettings() throws Exception {
        state = "INTERJECTION_PENDING";
        CompletableFuture<JsonObject> triggered = new CompletableFuture<>();
        var consumer = vertx.eventBus().<JsonObject>consumer("group.character.respond")
                .handler(event -> triggered.complete(event.body()));
        consumer.completionHandlerAndAwait();
        service.approveInterjection(request, owner).await().indefinitely();
        JsonObject event = triggered.get(2, TimeUnit.SECONDS);
        assertEquals("CHARACTER_RESPONDING", state);
        assertEquals(second, event.getString("characterId"));
        assertEquals(owner, event.getString("userId"));
        assertEquals("consumed", requestStatus);
    }

    @Test void approvalDuringAResponseKeepsTheCurrentSpeakerAndIsConsumedOnce() {
        state = "CHARACTER_RESPONDING";
        speaker = first;
        service.approveInterjection(request, owner).await().indefinitely();
        assertEquals(first, speaker);
        assertEquals("CHARACTER_RESPONDING", state);
        assertEquals("approved", requestStatus);
        assertEquals(second, service.selectNextCharacter(chat).await().indefinitely().getId().toString());
        assertEquals("consumed", requestStatus);
        lastSpeaker = second;
        assertEquals(first, service.selectNextCharacter(chat).await().indefinitely().getId().toString());
        assertThrows(RuntimeException.class, () -> service.approveInterjection(request, owner).await().indefinitely());
    }

    @Test void approvalWhilePausedDoesNotStartAResponse() {
        state = "PAUSED";
        service.approveInterjection(request, owner).await().indefinitely();
        assertEquals("PAUSED", state);
        assertNull(speaker);
        assertEquals("approved", requestStatus);
    }

    @Test void resumingStartsAnApprovedCharacterInterjection() throws Exception {
        state = "PAUSED";
        requestStatus = "approved";
        CompletableFuture<JsonObject> triggered = new CompletableFuture<>();
        var consumer = vertx.eventBus().<JsonObject>consumer("group.character.respond")
                .handler(event -> triggered.complete(event.body()));
        consumer.completionHandlerAndAwait();
        var conversation = service.resumeGroupConversation(chat, owner).await().indefinitely();
        assertEquals(second, triggered.get(2, TimeUnit.SECONDS).getString("characterId"));
        assertEquals("CHARACTER_RESPONDING", conversation.getGroupState());
        assertEquals("consumed", requestStatus);
    }

    @Test void resumeCannotRevokeAnActiveTurn() {
        state = "CHARACTER_RESPONDING";
        speaker = first;
        assertThrows(IllegalStateException.class, () -> service.resumeGroupConversation(chat, owner).await().indefinitely());
        assertEquals("CHARACTER_RESPONDING", state);
        assertEquals(first, speaker);
    }

    @Test void requestingAnInterjectionDoesNotRevokeAnActiveCharacterTurnAndSetsExpiry() {
        state = "CHARACTER_RESPONDING";
        speaker = first;
        service.requestInterjection(chat, second, owner, "Let me speak", 1).await().indefinitely();
        assertEquals("CHARACTER_RESPONDING", state);
        assertEquals(first, speaker);
        assertEquals(requestedAt.plusMinutes(5), expiresAt);
    }

    @Test void resolvedInterjectionsCannotBeApprovedAgain() {
        requestStatus = "rejected";
        assertThrows(RuntimeException.class, () -> service.approveInterjection(request, owner).await().indefinitely());
        assertEquals("rejected", requestStatus);
    }

    @Test void expiryNotifiesConnectedClientsWhenAnIdleConversationIsReleased() throws Exception {
        CompletableFuture<JsonObject> changed = new CompletableFuture<>();
        var consumer = vertx.eventBus().<JsonObject>consumer("group:" + chat + ":state-changed")
                .handler(event -> changed.complete(event.body()));
        consumer.completionHandlerAndAwait();
        service.pool = pool((sql, params) -> sql.contains("SELECT c.conversationGUID")
                ? rows(row(Map.of("conversationGUID", chat))) : affected(1));
        var cleanup = GroupChatService.class.getDeclaredMethod("cleanupExpiredInterjections");
        cleanup.setAccessible(true);
        ((Uni<?>) cleanup.invoke(service)).await().indefinitely();
        assertEquals("WAITING_FOR_USER", changed.get(2, TimeUnit.SECONDS).getString("groupState"));
    }

    @Test void arrivalTurnCommitsTheSpeakerBeforeItSendsTheResponse() throws Exception {
        CompletableFuture<JsonObject> triggered = new CompletableFuture<>();
        var consumer = vertx.eventBus().<JsonObject>consumer("group.character.respond")
                .handler(event -> triggered.complete(
                        event.body().copy().put("speakerAtDelivery", speaker)));
        consumer.completionHandlerAndAwait();

        service.triggerArrivalResponse(chat, owner).await().indefinitely();

        JsonObject event = triggered.get(2, TimeUnit.SECONDS);
        assertEquals("CHARACTER_RESPONDING", state);
        assertNotNull(event.getString("speakerAtDelivery"), "speaker must be stored before the event");
        assertEquals(event.getString("characterId"), event.getString("speakerAtDelivery"));
        assertEquals(owner, event.getString("userId"));
        assertTrue(event.getBoolean("autoTriggered"));
    }

    @Test void arrivalTurnReleasesTheClaimWhenNobodyCanSpeak() throws Exception {
        introduced.put(first, false);
        introduced.put(second, false);
        CompletableFuture<Boolean> delivered = new CompletableFuture<>();
        CompletableFuture<JsonObject> released = new CompletableFuture<>();
        vertx.eventBus().<JsonObject>consumer("group.character.respond")
                .handler(event -> delivered.complete(true));
        vertx.eventBus().<JsonObject>consumer("group:" + chat + ":state-changed")
                .handler(event -> released.complete(event.body()));
        vertx.setTimer(200, ignored -> delivered.complete(false));

        service.triggerArrivalResponse(chat, owner).await().indefinitely();

        assertEquals("WAITING_FOR_USER", state);
        assertNull(speaker);
        assertFalse(delivered.get(2, TimeUnit.SECONDS), "no turn may be sent");
        assertEquals("WAITING_FOR_USER", released.get(2, TimeUnit.SECONDS).getString("groupState"),
                "the client must hear that the turn was released");
    }

    @Test void aUserMessageStartsATurnAndResetsTheBudgetInsideTheClaim() throws Exception {
        state = "INTERJECTION_PENDING";
        List<String> claims = new ArrayList<>();
        service.pool = pool((sql, params) -> {
            if (sql.contains("SET groupState = 'CHARACTER_RESPONDING'")) {
                claims.add(sql);
                state = "CHARACTER_RESPONDING";
                return affected(1);
            }
            return execute(sql, params);
        });
        CompletableFuture<JsonObject> triggered = new CompletableFuture<>();
        vertx.eventBus().<JsonObject>consumer("group.character.respond")
                .handler(event -> triggered.complete(event.body()));

        Character started = service.startCharacterTurn(chat, owner).await().indefinitely();

        assertNotNull(started);
        JsonObject event = triggered.get(2, TimeUnit.SECONDS);
        assertEquals("CHARACTER_RESPONDING", state);
        assertEquals(event.getString("characterId"), speaker);
        assertFalse(event.getBoolean("autoTriggered"));
        assertEquals(owner, event.getString("userId"));
        assertEquals(1, claims.size());
        assertTrue(claims.getFirst().contains("consecutiveResponseCount = 0"),
                "the per-message budget resets where the turn is claimed");
        assertTrue(claims.getFirst().contains("'INTERJECTION_PENDING'"),
                "a pending interjection must not swallow a user message");
    }

    @Test void regenerationForcesTheNamedCharacterThroughTheSameClaim() throws Exception {
        CompletableFuture<JsonObject> triggered = new CompletableFuture<>();
        vertx.eventBus().<JsonObject>consumer("group.character.respond")
                .handler(event -> triggered.complete(event.body()));

        Character started = service.startCharacterTurnFor(chat, second, owner).await().indefinitely();

        assertEquals(second, started.getId().toString());
        JsonObject event = triggered.get(2, TimeUnit.SECONDS);
        assertEquals(second, event.getString("characterId"), "regeneration re-runs the character whose message was removed");
        assertEquals(second, speaker);
    }

    @Test void regenerationCannotForceACharacterWhoLeftTheGroup() {
        service.pool = pool((sql, params) -> {
            if (sql.contains("COUNT(*)")) return rows(row(Map.of("count", 0L)));
            return execute(sql, params);
        });

        assertNull(service.startCharacterTurnFor(chat, second, owner).await().indefinitely());

        assertEquals("WAITING_FOR_USER", state);
        assertNull(speaker);
    }

    @Test void aMessageThatLosesTheClaimStartsNothing() throws Exception {
        state = "CHARACTER_RESPONDING";
        speaker = first;
        CompletableFuture<Boolean> delivered = new CompletableFuture<>();
        vertx.eventBus().<JsonObject>consumer("group.character.respond")
                .handler(event -> delivered.complete(true));
        vertx.setTimer(200, ignored -> delivered.complete(false));

        assertNull(service.startCharacterTurn(chat, owner).await().indefinitely());

        assertEquals("CHARACTER_RESPONDING", state);
        assertEquals(first, speaker);
        assertFalse(delivered.get(2, TimeUnit.SECONDS), "a busy conversation must not gain a second speaker");
    }

    @Test void aRequestForTheFloorIsRecordedButStillLeavesTheClaimFree() throws Exception {
        service.requestInterjection(chat, null, owner, "I want to speak", 1).await().indefinitely();
        assertEquals("INTERJECTION_PENDING", state, "the header keeps a pending request visible");
        assertEquals("pending", requestStatus);

        // The same state a request leaves behind must still start a turn for
        // the user's next message, which a lost trigger used to swallow.
        CompletableFuture<JsonObject> triggered = new CompletableFuture<>();
        vertx.eventBus().<JsonObject>consumer("group.character.respond")
                .handler(event -> triggered.complete(event.body()));
        Character started = service.startCharacterTurn(chat, owner).await().indefinitely();

        assertNotNull(started);
        assertEquals(started.getId().toString(), triggered.get(2, TimeUnit.SECONDS).getString("characterId"));
    }

    private RowSet<Row> execute(String sql, Tuple parameters) {
        if (sql.contains("UPDATE interjection_request ir")) {
            if (!"pending".equals(requestStatus)) return affected(0);
            requestStatus = parameters.getString(0);
            return affected(1);
        }
        if (sql.contains("SELECT * FROM interjection_request WHERE id")) return rows(interjection());
        if (sql.contains("SET status = 'consumed'")) {
            if (!"approved".equals(requestStatus)) return affected(0);
            requestStatus = "consumed";
            return affected(1);
        }
        if (sql.contains("SET isIntroduced = TRUE")) return affected(1);
        if (sql.stripLeading().startsWith("SELECT * FROM interjection_request")) {
            return "approved".equals(requestStatus) || "pending".equals(requestStatus) ? rows(interjection()) : rows();
        }
        if (sql.contains("UPDATE conversation SET groupState = ?")) {
            String target = parameters.getString(0);
            if ("WAITING_FOR_USER".equals(target) && !"PAUSED".equals(state)) return affected(0);
            state = target;
            speaker = null;
            return affected(1);
        }
        if (sql.contains("SET groupState = 'CHARACTER_RESPONDING'")) {
            if (!Set.of("WAITING_FOR_USER", "INTERJECTION_PENDING").contains(state)) return affected(0);
            state = "CHARACTER_RESPONDING";
            return affected(1);
        }
        if (sql.contains("SET groupState = 'WAITING_FOR_USER', currentSpeakerId = NULL")) {
            if (!"CHARACTER_RESPONDING".equals(state)) return affected(0);
            state = "WAITING_FOR_USER";
            speaker = null;
            return affected(1);
        }
        if (sql.contains("SET currentSpeakerId = ?")) {
            speaker = parameters.getString(0);
            return affected(1);
        }
        if (sql.contains("SELECT ownerId")) return rows(row(Map.of("ownerId", owner)));
        if (sql.contains("SELECT c.*")) {
            Map<String, Object> values = new HashMap<>(Map.of("conversationGUID", chat, "ownerId", owner,
                    "turnMode", mode, "groupState", state, "lastSpeakerId", lastSpeaker,
                    "consecutiveResponseCount", 3, "maxConsecutiveResponses", 3));
            if (speaker != null) values.put("currentSpeakerId", speaker);
            return rows(row(values));
        }
        if (sql.contains("SELECT cc.*")) return rows(characterMember(first, 0, 1e20), characterMember(second, 1, 0.01));
        if (sql.contains("FROM `character`")) return rows(row(Map.of("characterUUID", parameters.getString(0), "name", "Character")));
        if (sql.contains("COUNT(*)")) return rows(row(Map.of("count", 1L)));
        if (sql.contains("SELECT c.groupState")) return rows(row(Map.of("groupState", state)));
        if (sql.contains("INSERT INTO interjection_request")) {
            requestedAt = parameters.getLocalDateTime(6);
            expiresAt = parameters.getLocalDateTime(7);
            return affected(1);
        }
        if (sql.contains("SET groupState = 'INTERJECTION_PENDING'")) {
            if (!"WAITING_FOR_USER".equals(state)) return affected(0);
            state = "INTERJECTION_PENDING";
            return affected(1);
        }
        throw new AssertionError("Unexpected query: " + sql);
    }

    private Row interjection() {
        return row(Map.of("id", request, "conversationId", chat, "requesterId", second,
                "requesterType", "character", "status", requestStatus, "priority", 1));
    }

    private Row characterMember(String id, int order, double weight) {
        Map<String, Object> values = new HashMap<>(Map.of(
                "id", UUID.randomUUID().toString(), "conversationId", chat, "characterId", id,
                "char_id", id, "isActive", true, "turnOrder", order, "weight", weight,
                "isIntroduced", introduced.getOrDefault(id, true),
                "responseCount", responseCounts.getOrDefault(id, 1)));
        if (entryTimes.containsKey(id)) values.put("introducedAt", entryTimes.get(id));
        return row(values);
    }
}
