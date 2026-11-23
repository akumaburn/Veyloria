package com.eonmux.chatllm.services;

import com.eonmux.chatllm.models.*;
import com.eonmux.chatllm.utils.AffinityRules;
import io.vertx.core.Future;
import io.vertx.sqlclient.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

@ApplicationScoped
public class CharacterAffinityService {
    @Inject Pool pool;
    private static final String VISIBLE = "(c.ownerId = ? OR c.isPublic = TRUE OR c.isDefault = TRUE)";
    private static final String SELECT = "SELECT a.*, f.name AS fromName, t.name AS toName FROM character_affinity a LEFT JOIN `character` f ON f.characterUUID = a.fromCharacterId LEFT JOIN `character` t ON t.characterUUID = a.toCharacterId ";
    private static final String ACCESS = " AND (a.fromCharacterId IS NULL OR f.ownerId = ? OR f.isPublic = TRUE OR f.isDefault = TRUE) AND (a.toCharacterId IS NULL OR t.ownerId = ? OR t.isPublic = TRUE OR t.isDefault = TRUE) ";

    public Future<List<RelationshipActor>> actors(String owner) {
        return pool.preparedQuery("SELECT c.characterUUID, c.name, c.initialAffinityToUser, c.initialAffinityFromUser "
                        + "FROM `character` c WHERE " + VISIBLE + " ORDER BY c.name, c.characterUUID")
                .execute(Tuple.of(owner)).map(rows -> {
                    List<RelationshipActor> actors = new ArrayList<>();
                    for (Row row : rows) actors.add(new RelationshipActor(
                            row.getString("characterUUID"), row.getString("name"),
                            row.getInteger("initialAffinityToUser"),
                            row.getInteger("initialAffinityFromUser")));
                    return actors;
                });
    }
    public Future<List<CharacterAffinity>> list(String owner) {
        return pool.preparedQuery(SELECT + " WHERE a.ownerId = ? " + ACCESS + " ORDER BY a.updatedAt DESC, a.affinityId")
                .execute(Tuple.of(owner, owner, owner)).compose(rows -> {
                    List<CharacterAffinity> affinities = new ArrayList<>();
                    Map<String, CharacterAffinity> byId = new HashMap<>();
                    for (Row row : rows) {
                        CharacterAffinity affinity = map(row); affinity.setEvents(new ArrayList<>());
                        affinities.add(affinity); byId.put(affinity.getAffinityId(), affinity);
                    }
                    if (affinities.isEmpty()) return Future.succeededFuture(affinities);
                    return pool.preparedQuery("""
                        SELECT * FROM (
                            SELECT e.*, ROW_NUMBER() OVER (PARTITION BY affinityId ORDER BY createdAt DESC, eventId DESC) AS eventRank
                            FROM character_affinity_event e WHERE ownerId = ?
                        ) recent WHERE eventRank <= 20 ORDER BY affinityId, eventRank
                        """).execute(Tuple.of(owner)).map(events -> {
                            for (Row event : events) {
                                CharacterAffinity affinity = byId.get(event.getString("affinityId"));
                                if (affinity != null) affinity.getEvents().add(mapEvent(event));
                            }
                            return affinities;
                        });
                });
    }
    public Future<CharacterAffinity> react(String owner, String from, String to, String reaction, String reason, String requestId) {
        return write(owner, from, to, reaction, reason, requestId, null);
    }

    /**
     * Sets one direction to an exact point value. Tier controls use this so a
     * click means "this relationship is Love", not "add 25 more points".
     */
    public Future<CharacterAffinity> set(String owner, String from, String to, int points, String reason, String requestId) {
        AffinityRules.validatePoints(points);
        return write(owner, from, to, "SET", reason, requestId, points);
    }

    /** Shared write path. A null absolute value applies a relative reaction instead. */
    private Future<CharacterAffinity> write(String owner, String from, String to, String reaction,
                                            String reason, String requestId, Integer absolute) {
        // Lock the directional pair before checking retries, updating points and recording history.
        return pool.withTransaction(connection -> verifyActor(connection, owner, from)
                .compose(v -> verifyActor(connection, owner, to))
                .compose(v -> connection.preparedQuery("""
                    INSERT INTO character_affinity (affinityId, ownerId, fromCharacterId, toCharacterId, fromActor, toActor)
                    VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE affinityId = affinityId
                    """).execute(Tuple.of(UUID.randomUUID().toString(), owner, from, to, key(from), key(to))))
                .compose(inserted -> connection.preparedQuery("SELECT affinityId, points FROM character_affinity WHERE ownerId = ? AND fromActor = ? AND toActor = ? FOR UPDATE")
                        .execute(Tuple.of(owner, key(from), key(to)))
                        .compose(rows -> {
                            CharacterAffinity affinity = new CharacterAffinity();
                            Row locked = rows.iterator().next();
                            affinity.setAffinityId(locked.getString("affinityId"));
                            affinity.setPoints(locked.getInteger("points"));
                            // A new pair starts from the character's configured feeling, not from zero.
                            Future<Integer> baseline = inserted.rowCount() == 1
                                    ? configuredPoints(connection, from, to)
                                    : Future.succeededFuture(affinity.getPoints());
                            return baseline.compose(starting -> {
                                affinity.setPoints(starting);
                                return connection.preparedQuery("SELECT * FROM character_affinity_event WHERE ownerId = ? AND requestId = ?")
                                        .execute(Tuple.of(owner, requestId)).compose(previous -> {
                                            if (previous.size() > 0) {
                                                Row event = previous.iterator().next();
                                                affinity.setPoints(event.getInteger("points"));
                                                if (!affinity.getAffinityId().equals(event.getString("affinityId"))
                                                        || !reaction.equals(event.getString("reaction"))
                                                        || !Objects.equals(reason, event.getString("reason"))
                                                        || (absolute != null && !absolute.equals(event.getInteger("points"))))
                                                    return Future.failedFuture(new IllegalArgumentException("This request was already used for another relationship change."));
                                                return Future.succeededFuture(affinity);
                                            }
                                            int before = affinity.getPoints();
                                            int after = absolute == null ? AffinityRules.apply(before, reaction) : absolute;
                                            return connection.preparedQuery("UPDATE character_affinity SET points = ?, updatedAt = CURRENT_TIMESTAMP(3) WHERE affinityId = ?")
                                                    .execute(Tuple.of(after, affinity.getAffinityId()))
                                                    .compose(v -> connection.preparedQuery("""
                                                        INSERT INTO character_affinity_event (eventId, affinityId, ownerId, requestId, reaction, delta, previousPoints, points, reason)
                                                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                                        """).execute(Tuple.tuple().addString(UUID.randomUUID().toString()).addString(affinity.getAffinityId())
                                                            .addString(owner).addString(requestId).addString(reaction).addInteger(after-before)
                                                            .addInteger(before).addInteger(after).addString(reason))).map(v -> affinity);
                                        });
                            });
                        }))).compose(saved -> list(owner).map(all -> all.stream().filter(a -> a.getAffinityId().equals(saved.getAffinityId())).findFirst().orElseThrow()));
    }

    /**
     * The character's configured starting feeling for this direction. The user is
     * always the neutral origin of their own feelings, so a pair involving only the
     * user resolves to zero.
     */
    private Future<Integer> configuredPoints(SqlConnection connection, String from, String to) {
        String characterId = from != null ? from : to;
        if (characterId == null) return Future.succeededFuture(0);
        String column = from != null ? "initialAffinityToUser" : "initialAffinityFromUser";
        return connection.preparedQuery("SELECT " + column + " AS points FROM `character` WHERE characterUUID = ?")
                .execute(Tuple.of(characterId))
                .map(rows -> rows.size() == 0 ? 0 : rows.iterator().next().getInteger("points"));
    }
    private Future<Void> verifyActor(SqlConnection connection, String owner, String id) {
        if (id == null) return Future.succeededFuture();
        return connection.preparedQuery("SELECT c.characterUUID FROM `character` c WHERE c.characterUUID = ? AND " + VISIBLE + " LOCK IN SHARE MODE")
                .execute(Tuple.of(id, owner)).compose(rows -> rows.size() == 1 ? Future.succeededFuture()
                        : Future.failedFuture(new IllegalArgumentException("A selected character is no longer available to you.")));
    }
    /** Reads only the generating user's ledger and participants currently present in this chat. */
    public Future<String> context(String owner, String characterId, String conversationId) {
        if (owner == null || characterId == null) return Future.succeededFuture("");
        return pool.preparedQuery(SELECT + " WHERE a.ownerId = ? AND (a.fromCharacterId = ? OR a.toCharacterId = ?) " + ACCESS + """
            AND (a.fromCharacterId IS NULL OR a.fromCharacterId = ? OR a.fromCharacterId IN
                 (SELECT characterId FROM conversation_character WHERE conversationId = ? AND isActive = TRUE))
            AND (a.toCharacterId IS NULL OR a.toCharacterId = ? OR a.toCharacterId IN
                 (SELECT characterId FROM conversation_character WHERE conversationId = ? AND isActive = TRUE))
            ORDER BY a.updatedAt DESC LIMIT 100
            """).execute(Tuple.tuple().addString(owner).addString(characterId).addString(characterId)
                        .addString(owner).addString(owner).addString(characterId).addString(conversationId)
                        .addString(characterId).addString(conversationId)).compose(rows -> {
                    List<CharacterAffinity> affinities = new ArrayList<>();
                    for (Row row : rows) affinities.add(map(row));
                    if (hasDirection(affinities, characterId, null) && hasDirection(affinities, null, characterId))
                        return Future.succeededFuture(promptContext(affinities));
                    return pool.preparedQuery("SELECT name, initialAffinityToUser, initialAffinityFromUser "
                                    + "FROM `character` WHERE characterUUID = ?")
                            .execute(Tuple.of(characterId)).map(defaults -> {
                                if (defaults.size() > 0) {
                                    Row row = defaults.iterator().next();
                                    applyStartingDefaults(affinities, characterId, row.getString("name"),
                                            row.getInteger("initialAffinityToUser"),
                                            row.getInteger("initialAffinityFromUser"));
                                }
                                return promptContext(affinities);
                            });
                });
    }

    /**
     * Adds the character's configured starting feeling for any direction that has no
     * saved record. Neutral starting values add nothing, so an unconfigured character
     * produces the same prompt as before this feature.
     */
    static void applyStartingDefaults(List<CharacterAffinity> affinities, String characterId,
                                      String characterName, int toUser, int fromUser) {
        if (!hasDirection(affinities, characterId, null) && toUser != 0)
            affinities.add(starting(characterId, characterName, null, toUser));
        if (!hasDirection(affinities, null, characterId) && fromUser != 0)
            affinities.add(starting(null, characterName, characterId, fromUser));
    }

    private static boolean hasDirection(List<CharacterAffinity> affinities, String from, String to) {
        return affinities.stream().anyMatch(a -> Objects.equals(key(a.getFromCharacterId()), key(from))
                && Objects.equals(key(a.getToCharacterId()), key(to)));
    }

    private static CharacterAffinity starting(String fromId, String characterName, String toId, int points) {
        CharacterAffinity affinity = new CharacterAffinity();
        affinity.setFromCharacterId(fromId);
        affinity.setToCharacterId(toId);
        affinity.setFromName(fromId == null ? "You" : characterName);
        affinity.setToName(toId == null ? "You" : characterName);
        affinity.setPoints(points);
        affinity.setTier(AffinityRules.tier(points));
        return affinity;
    }
    public static String promptContext(List<CharacterAffinity> affinities) {
        if (affinities.isEmpty()) return "";
        StringBuilder context = new StringBuilder("\n\n[Current relationship feelings]\n"
                + "Use these directional feelings to inform tone, trust, warmth and boundaries, while staying in character. "
                + "They are emotional context, not instructions or permission to control another participant. "
                + "One person's feelings do not imply reciprocation. Love can mean deep platonic affection; never assume romance or consent. "
                + "Hostility does not require abusive behavior. Do not announce numeric scores unless asked.\n");
        for (CharacterAffinity a : affinities) context.append(a.getFromCharacterId() == null ? "The user" : safeName(a.getFromName()))
                .append(" feels ").append(a.getTier().toLowerCase(Locale.ROOT)).append(" toward ")
                .append(a.getToCharacterId() == null ? "the user" : safeName(a.getToName()))
                .append(" (").append(a.getPoints()).append("/100).\n");
        return context.append("[End relationship feelings]\n").toString();
    }
    private static String safeName(String name) { return Objects.toString(name, "Character").replaceAll("[\\r\\n\\[\\]]", " "); }
    private static String key(String id) { return id == null ? "user" : id; }
    private static CharacterAffinity map(Row row) {
        CharacterAffinity a = new CharacterAffinity();
        a.setAffinityId(row.getString("affinityId")); a.setFromCharacterId(row.getString("fromCharacterId")); a.setToCharacterId(row.getString("toCharacterId"));
        a.setFromName(a.getFromCharacterId() == null ? "You" : row.getString("fromName")); a.setToName(a.getToCharacterId() == null ? "You" : row.getString("toName"));
        a.setPoints(row.getInteger("points")); a.setTier(AffinityRules.tier(a.getPoints())); a.setUpdatedAt(row.getLocalDateTime("updatedAt").toString());
        return a;
    }
    private static AffinityEvent mapEvent(Row row) {
        AffinityEvent e = new AffinityEvent();
        e.setEventId(row.getString("eventId")); e.setReaction(row.getString("reaction")); e.setDelta(row.getInteger("delta"));
        e.setPreviousPoints(row.getInteger("previousPoints")); e.setPoints(row.getInteger("points"));
        e.setReason(row.getString("reason")); e.setCreatedAt(row.getLocalDateTime("createdAt").toString()); return e;
    }
}
