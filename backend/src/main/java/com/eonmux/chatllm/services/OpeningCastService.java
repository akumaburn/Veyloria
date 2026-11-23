package com.eonmux.chatllm.services;

import com.eonmux.chatllm.utils.OpeningCastSelector;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the opening cast onstage for a new scene.
 *
 * <p>Conversation creation inserts the first roster members as a safe
 * provisional cast. This service then replaces that provisional choice with
 * the group that best fits the scenario and the roster's recorded feelings. A
 * failed selection leaves the provisional cast in place, so creation never
 * leaves a scene with nobody to speak.
 */
@ApplicationScoped
@Slf4j
public class OpeningCastService {
    private static final Pattern CANONICAL_IDENTITY =
            Pattern.compile("Canonical identity:\\s*[^;\\n]+;\\s*([^;\\n]+);");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final int MAX_ROLE_LENGTH = 160;

    @Inject
    Pool pool;

    /**
     * Includes a story template's title, genre, description, and suggested
     * archetypes in the scenario signal.
     */
    public Uni<Void> selectOpeningCast(String conversationId, String ownerId, String scenario,
                                       String storyTemplateId, int size) {
        return templateContext(storyTemplateId)
                .chain(template -> selectOpeningCast(conversationId, ownerId,
                        scenario == null ? template : scenario + "\n" + template, size));
    }

    public Uni<Void> selectOpeningCast(String conversationId, String ownerId, String scenario, int size) {
        if (conversationId == null || ownerId == null || size <= 0) {
            return Uni.createFrom().voidItem();
        }
        return loadCast(conversationId)
                .chain(cast -> cast.isEmpty()
                        ? Uni.createFrom().<List<String>>item(List.of())
                        : loadAffinity(ownerId, cast.stream().map(CastRow::id).toList())
                            .map(affinity -> OpeningCastSelector.select(
                                    cast.stream().map(CastRow::candidate).toList(),
                                    affinity, scenario, size)))
                .chain(selected -> selected.isEmpty()
                        ? Uni.createFrom().voidItem()
                        : applySelection(conversationId, selected))
                .onFailure().invoke(error -> log.warn(
                        "Opening cast selection failed for conversation {}: {}",
                        conversationId, error.getMessage()))
                .onFailure().recoverWithItem((Void) null);
    }

    private Uni<String> templateContext(String templateId) {
        if (templateId == null || templateId.isBlank()) return Uni.createFrom().item("");
        return pool.preparedQuery("""
                        SELECT name, genre, description, suggestedCharacterArchetypes, defaultWorldState
                        FROM story_template WHERE templateId = ?
                        """)
                .execute(Tuple.of(templateId))
                .map(rows -> {
                    if (rows.size() == 0) return "";
                    Row row = rows.iterator().next();
                    StringBuilder context = new StringBuilder();
                    for (String value : List.of(
                            value(row, "name"), value(row, "genre"), value(row, "description"),
                            value(row, "suggestedCharacterArchetypes"), value(row, "defaultWorldState"))) {
                        if (!value.isBlank()) context.append(value).append(' ');
                    }
                    return context.toString().strip();
                })
                .onFailure().recoverWithItem("");
    }

    private static String value(Row row, String column) {
        String value = row.getString(column);
        return value == null ? "" : value;
    }

    private Uni<List<CastRow>> loadCast(String conversationId) {
        return pool.preparedQuery("""
                SELECT cc.characterId, cc.turnOrder,
                       c.name, c.description, c.personality, c.backstory,
                       c.goals, c.systemPrompt, c.greeting
                FROM conversation_character cc
                JOIN `character` c ON cc.characterId = c.characterUUID
                WHERE cc.conversationId = ? AND cc.isActive = TRUE
                ORDER BY cc.turnOrder, cc.characterId
                """)
                .execute(Tuple.of(conversationId))
                .map(rows -> {
                    List<CastRow> cast = new ArrayList<>();
                    for (Row row : rows) {
                        cast.add(new CastRow(
                                row.getString("characterId"),
                                row.getString("name"),
                                row.getInteger("turnOrder") == null ? 0 : row.getInteger("turnOrder"),
                                role(row.getString("name"), row.getString("description"),
                                        row.getString("systemPrompt")),
                                // The system prompt repeats the shared setting and the
                                // whole cast, so it would add the same noise to every
                                // candidate. Score only character-specific text.
                                text(row.getString("description"), row.getString("personality"),
                                        row.getString("backstory"), row.getString("goals"),
                                        row.getString("greeting"))));
                    }
                    return cast;
                });
    }

    private Uni<Map<String, Map<String, Integer>>> loadAffinity(String ownerId, List<String> characterIds) {
        if (characterIds.isEmpty()) return Uni.createFrom().item(Map.of());
        String placeholders = String.join(",", Collections.nCopies(characterIds.size(), "?"));
        String query = "SELECT fromActor, toActor, points FROM character_affinity "
                + "WHERE ownerId = ? AND fromActor IN (" + placeholders + ")"
                + " AND toActor IN (" + placeholders + ")";
        Tuple params = Tuple.tuple().addValue(ownerId);
        characterIds.forEach(params::addValue);
        characterIds.forEach(params::addValue);

        return pool.preparedQuery(query).execute(params).map(rows -> {
            Map<String, Map<String, Integer>> affinity = new HashMap<>();
            for (Row row : rows) {
                String from = row.getString("fromActor");
                String to = row.getString("toActor");
                if (from == null || to == null) continue;
                affinity.computeIfAbsent(from, ignored -> new HashMap<>())
                        .put(to, row.getInteger("points") == null ? 0 : row.getInteger("points"));
            }
            return affinity;
        });
    }

    /**
     * Replaces the provisional cast inside one transaction. A failure rolls
     * back to the cast that creation already marked as present.
     */
    private Uni<Void> applySelection(String conversationId, List<String> selected) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String placeholders = String.join(",", Collections.nCopies(selected.size(), "?"));
        return pool.withTransaction(client -> client.preparedQuery("""
                        UPDATE conversation_character
                        SET isIntroduced = FALSE, introducedAt = NULL
                        WHERE conversationId = ?
                        """)
                .execute(Tuple.of(conversationId))
                .chain(ignored -> {
                    Tuple params = Tuple.tuple().addValue(now).addValue(conversationId);
                    selected.forEach(params::addValue);
                    return client.preparedQuery("""
                                    UPDATE conversation_character
                                    SET isIntroduced = TRUE, introducedAt = COALESCE(introducedAt, ?)
                                    WHERE conversationId = ? AND characterId IN (%s)
                                    """.formatted(placeholders))
                            .execute(params);
                })
                .replaceWithVoid());
    }

    private static String role(String name, String description, String systemPrompt) {
        if (systemPrompt != null) {
            Matcher matcher = CANONICAL_IDENTITY.matcher(systemPrompt);
            if (matcher.find()) return trimRole(matcher.group(1));
        }
        if (description != null && !description.isBlank()) {
            return trimRole(SENTENCE_END.split(description.strip(), 2)[0]);
        }
        return name == null ? "character" : name;
    }

    private static String trimRole(String value) {
        String role = value == null ? "" : value.strip();
        return role.length() <= MAX_ROLE_LENGTH ? role : role.substring(0, MAX_ROLE_LENGTH).strip();
    }

    private static String text(String... values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            text.append(value).append(' ');
        }
        return text.toString();
    }

    private record CastRow(String id, String name, int order, String role, String text) {
        private OpeningCastSelector.Candidate candidate() {
            return new OpeningCastSelector.Candidate(id, name, order, role, text);
        }
    }
}
