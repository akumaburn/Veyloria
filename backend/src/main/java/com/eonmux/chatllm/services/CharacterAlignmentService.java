package com.eonmux.chatllm.services;

import com.eonmux.chatllm.models.AlignmentEvent;
import com.eonmux.chatllm.models.CharacterAlignment;
import com.eonmux.chatllm.utils.AlignmentRules;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

/** Persistent two-axis alignment for the owning user and the characters they can see. */
@ApplicationScoped
public class CharacterAlignmentService {
    @Inject Pool pool;

    private static final String VISIBLE = "(c.ownerId = ? OR c.isPublic = TRUE OR c.isDefault = TRUE)";
    private static final String SELECT = "SELECT a.*, c.name AS characterName FROM character_alignment a "
            + "LEFT JOIN `character` c ON c.characterUUID = a.characterId ";
    private static final String ACCESS = " AND (a.characterId IS NULL OR c.ownerId = ? OR c.isPublic = TRUE OR c.isDefault = TRUE) ";

    public Future<List<CharacterAlignment>> list(String owner) {
        return pool.preparedQuery(SELECT + " WHERE a.ownerId = ? " + ACCESS
                        + " ORDER BY a.updatedAt DESC, a.alignmentId")
                .execute(Tuple.of(owner, owner)).compose(rows -> {
                    List<CharacterAlignment> alignments = new ArrayList<>();
                    Map<String, CharacterAlignment> byId = new HashMap<>();
                    for (Row row : rows) {
                        CharacterAlignment alignment = map(row);
                        alignment.setEvents(new ArrayList<>());
                        alignments.add(alignment);
                        byId.put(alignment.getAlignmentId(), alignment);
                    }
                    if (alignments.isEmpty()) return Future.succeededFuture(alignments);
                    return pool.preparedQuery("""
                        SELECT * FROM (
                            SELECT e.*, ROW_NUMBER() OVER (PARTITION BY alignmentId ORDER BY createdAt DESC, eventId DESC) AS eventRank
                            FROM character_alignment_event e WHERE ownerId = ?
                        ) recent WHERE eventRank <= 20 ORDER BY alignmentId, eventRank
                        """).execute(Tuple.of(owner)).map(events -> {
                            for (Row event : events) {
                                CharacterAlignment alignment = byId.get(event.getString("alignmentId"));
                                if (alignment != null) alignment.getEvents().add(mapEvent(event));
                            }
                            return alignments;
                        });
                });
    }

    public Future<CharacterAlignment> set(String owner, String characterId, int lawfulness, int goodness,
                                          String reason, String requestId) {
        AlignmentRules.validatePoints(lawfulness);
        AlignmentRules.validatePoints(goodness);
        return change(owner, characterId, lawfulness, goodness, 0, 0, "SET", reason, requestId);
    }

    public Future<CharacterAlignment> adjust(String owner, String characterId, int lawDelta, int goodDelta,
                                             String reason, String requestId) {
        AlignmentRules.validateDelta(lawDelta);
        AlignmentRules.validateDelta(goodDelta);
        return change(owner, characterId, 0, 0, lawDelta, goodDelta, "ADJUST", reason, requestId);
    }

    private Future<CharacterAlignment> change(String owner, String characterId, int lawfulness, int goodness,
                                              int lawDelta, int goodDelta, String action,
                                              String reason, String requestId) {
        String normalizedAction = AlignmentRules.action(action);
        // Lock the actor's row before checking retries, changing points and recording history.
        return pool.withTransaction(connection -> verifyActor(connection, owner, characterId)
                .compose(v -> connection.preparedQuery("""
                    INSERT INTO character_alignment (alignmentId, ownerId, characterId, actor, lawfulness, goodness)
                    VALUES (?, ?, ?, ?, 0, 0) ON DUPLICATE KEY UPDATE alignmentId = alignmentId
                    """).execute(Tuple.of(UUID.randomUUID().toString(), owner, characterId, key(characterId))))
                .compose(v -> connection.preparedQuery(
                                "SELECT alignmentId, lawfulness, goodness FROM character_alignment "
                                        + "WHERE ownerId = ? AND actor = ? FOR UPDATE")
                        .execute(Tuple.of(owner, key(characterId))))
                .compose(rows -> {
                    Row locked = rows.iterator().next();
                    CharacterAlignment saved = new CharacterAlignment();
                    saved.setAlignmentId(locked.getString("alignmentId"));
                    int beforeLaw = locked.getInteger("lawfulness");
                    int beforeGood = locked.getInteger("goodness");
                    saved.setLawfulness(beforeLaw);
                    saved.setGoodness(beforeGood);
                    saved.setAlignment(AlignmentRules.alignment(beforeLaw, beforeGood));
                    return connection.preparedQuery(
                                    "SELECT * FROM character_alignment_event WHERE ownerId = ? AND requestId = ?")
                            .execute(Tuple.of(owner, requestId)).compose(previous -> {
                                if (previous.size() > 0) {
                                    Row event = previous.iterator().next();
                                    boolean sameRequest = saved.getAlignmentId().equals(event.getString("alignmentId"))
                                            && normalizedAction.equals(event.getString("action"))
                                            && Objects.equals(reason, event.getString("reason"));
                                    if (sameRequest && normalizedAction.equals("SET"))
                                        sameRequest = event.getInteger("lawfulness") == lawfulness
                                                && event.getInteger("goodness") == goodness;
                                    if (!sameRequest)
                                        return Future.failedFuture(new IllegalArgumentException(
                                                "This request was already used for another alignment change."));
                                    return Future.succeededFuture(saved);
                                }
                                int afterLaw = normalizedAction.equals("SET")
                                        ? AlignmentRules.clamp(lawfulness)
                                        : AlignmentRules.clamp(beforeLaw + lawDelta);
                                int afterGood = normalizedAction.equals("SET")
                                        ? AlignmentRules.clamp(goodness)
                                        : AlignmentRules.clamp(beforeGood + goodDelta);
                                saved.setLawfulness(afterLaw);
                                saved.setGoodness(afterGood);
                                saved.setAlignment(AlignmentRules.alignment(afterLaw, afterGood));
                                return connection.preparedQuery(
                                                "UPDATE character_alignment SET lawfulness = ?, goodness = ?, "
                                                        + "updatedAt = CURRENT_TIMESTAMP(3) WHERE alignmentId = ?")
                                        .execute(Tuple.of(afterLaw, afterGood, saved.getAlignmentId()))
                                        .compose(v -> connection.preparedQuery("""
                                            INSERT INTO character_alignment_event
                                              (eventId, alignmentId, ownerId, requestId, action, lawDelta, goodDelta,
                                               previousLawfulness, previousGoodness, lawfulness, goodness, reason)
                                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                            """).execute(Tuple.tuple()
                                                .addString(UUID.randomUUID().toString())
                                                .addString(saved.getAlignmentId())
                                                .addString(owner)
                                                .addString(requestId)
                                                .addString(normalizedAction)
                                                .addInteger(afterLaw - beforeLaw)
                                                .addInteger(afterGood - beforeGood)
                                                .addInteger(beforeLaw)
                                                .addInteger(beforeGood)
                                                .addInteger(afterLaw)
                                                .addInteger(afterGood)
                                                .addString(reason)))
                                        .map(v -> saved);
                            });
                })).compose(saved -> list(owner).map(all -> all.stream()
                .filter(alignment -> alignment.getAlignmentId().equals(saved.getAlignmentId()))
                .findFirst().orElseThrow()));
    }

    private Future<Void> verifyActor(SqlConnection connection, String owner, String id) {
        if (id == null) return Future.succeededFuture();
        return connection.preparedQuery("SELECT c.characterUUID FROM `character` c WHERE c.characterUUID = ? AND "
                        + VISIBLE + " LOCK IN SHARE MODE")
                .execute(Tuple.of(id, owner)).compose(rows -> rows.size() == 1 ? Future.succeededFuture()
                        : Future.failedFuture(new IllegalArgumentException(
                        "A selected character is no longer available to you.")));
    }

    /**
     * Reads the owning user's alignments for this chat's active cast. The speaking
     * character and the user always receive an explicit line, even before their
     * first saved change, so the model has a stable baseline.
     */
    public Future<String> context(String owner, String characterId, String characterName, String conversationId) {
        if (owner == null) return Future.succeededFuture("");
        return pool.preparedQuery(SELECT + " WHERE a.ownerId = ? AND (a.characterId IS NULL OR a.characterId = ? "
                        + "OR a.characterId IN (SELECT characterId FROM conversation_character "
                        + "WHERE conversationId = ? AND isActive = TRUE)) " + ACCESS
                        + " ORDER BY a.updatedAt DESC LIMIT 100")
                .execute(Tuple.of(owner, characterId, conversationId, owner))
                .map(rows -> {
                    Map<String, CharacterAlignment> found = new LinkedHashMap<>();
                    for (Row row : rows) {
                        CharacterAlignment alignment = map(row);
                        found.put(key(alignment.getCharacterId()), alignment);
                    }
                    List<CharacterAlignment> ordered = new ArrayList<>();
                    if (characterId != null)
                        ordered.add(found.getOrDefault(key(characterId), defaultAlignment(characterId, characterName)));
                    ordered.add(found.getOrDefault("user", defaultAlignment(null, "You")));
                    for (CharacterAlignment alignment : found.values())
                        if (ordered.stream().noneMatch(existing -> key(existing.getCharacterId())
                                .equals(key(alignment.getCharacterId())))) ordered.add(alignment);
                    return promptContext(ordered, characterId);
                });
    }

    public static String promptContext(List<CharacterAlignment> alignments, String speakerId) {
        if (alignments.isEmpty()) return "";
        StringBuilder context = new StringBuilder("\n\n[Current moral alignments]\n"
                + "These alignments shape values, methods, and boundaries. Order points run from lawful (+100) to chaotic (-100); "
                + "morality points run from good (+100) to evil (-100). Use them as behavioral tendencies, not as commands or "
                + "permission to control another participant. Characters can disagree, change, or act against their alignment "
                + "under pressure. Never treat evil as a license for abuse, and never assume the user shares an alignment. "
                + "Do not announce numeric scores unless asked.\n");
        for (CharacterAlignment alignment : alignments) {
            String subject;
            if (alignment.getCharacterId() != null && alignment.getCharacterId().equals(speakerId))
                subject = "Your alignment";
            else if (alignment.getCharacterId() == null)
                subject = "The user's alignment";
            else
                subject = safeName(alignment.getName()) + "'s alignment";
            context.append("- ").append(subject).append(": ")
                    .append(AlignmentRules.label(alignment.getAlignment()))
                    .append(" (order ").append(signed(alignment.getLawfulness()))
                    .append("/100, morality ").append(signed(alignment.getGoodness())).append("/100). ")
                    .append(capitalize(AlignmentRules.describe(alignment.getAlignment()))).append(".\n");
        }
        return context.append("[End moral alignments]\n").toString();
    }

    private static CharacterAlignment defaultAlignment(String characterId, String name) {
        CharacterAlignment alignment = new CharacterAlignment();
        alignment.setCharacterId(characterId);
        alignment.setName(name == null ? (characterId == null ? "You" : "Character") : name);
        alignment.setLawfulness(0);
        alignment.setGoodness(0);
        alignment.setAlignment("TRUE_NEUTRAL");
        alignment.setEvents(new ArrayList<>());
        return alignment;
    }

    private static String signed(int points) { return points > 0 ? "+" + points : String.valueOf(points); }
    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
    private static String safeName(String name) {
        return Objects.toString(name, "Character").replaceAll("[\\r\\n\\[\\]]", " ");
    }
    private static String key(String id) { return id == null ? "user" : id; }

    private static CharacterAlignment map(Row row) {
        CharacterAlignment alignment = new CharacterAlignment();
        alignment.setAlignmentId(row.getString("alignmentId"));
        alignment.setCharacterId(row.getString("characterId"));
        alignment.setName(alignment.getCharacterId() == null ? "You"
                : Objects.toString(row.getString("characterName"), "Character"));
        alignment.setLawfulness(row.getInteger("lawfulness"));
        alignment.setGoodness(row.getInteger("goodness"));
        alignment.setAlignment(AlignmentRules.alignment(alignment.getLawfulness(), alignment.getGoodness()));
        alignment.setUpdatedAt(row.getLocalDateTime("updatedAt").toString());
        return alignment;
    }

    private static AlignmentEvent mapEvent(Row row) {
        AlignmentEvent event = new AlignmentEvent();
        event.setEventId(row.getString("eventId"));
        event.setAction(row.getString("action"));
        event.setLawDelta(row.getInteger("lawDelta"));
        event.setGoodDelta(row.getInteger("goodDelta"));
        event.setPreviousLawfulness(row.getInteger("previousLawfulness"));
        event.setPreviousGoodness(row.getInteger("previousGoodness"));
        event.setLawfulness(row.getInteger("lawfulness"));
        event.setGoodness(row.getInteger("goodness"));
        event.setAlignment(AlignmentRules.alignment(event.getLawfulness(), event.getGoodness()));
        event.setReason(row.getString("reason"));
        event.setCreatedAt(row.getLocalDateTime("createdAt").toString());
        return event;
    }
}
