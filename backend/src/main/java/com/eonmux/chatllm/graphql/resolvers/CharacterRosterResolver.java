package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.CharacterDraft;
import com.eonmux.chatllm.models.CharacterDraftRelationship;
import com.eonmux.chatllm.models.inputs.RosterMemberInput;
import com.eonmux.chatllm.models.inputs.RosterRelationshipInput;
import com.eonmux.chatllm.utils.AffinityRules;
import com.eonmux.chatllm.utils.AlignmentRules;
import com.eonmux.chatllm.utils.CharacterRosterGenerator;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@GraphQLApi
@ApplicationScoped
public class CharacterRosterResolver {
    @Inject Vertx vertx;
    @Inject Pool mysqlPool;
    @Inject GraphQLAuthContext graphQLAuthContext;

    @Mutation
    public @NonNull CompletableFuture<List<@NonNull CharacterDraft>> generateCharacterRoster(
            @Name("prompt") @NonNull String prompt, @Name("count") int count) throws GraphQLException {
        var auth = graphQLAuthContext.requireAuth();
        try { CharacterRosterGenerator.validateRequest(prompt, count); }
        catch (IllegalArgumentException error) { throw new GraphQLException(error.getMessage()); }
        JsonObject request = new JsonObject().put("prompt", prompt.strip()).put("rosterCount", count)
                .put("userId", auth.getUserId().toString()).put("temperature", 0.8f);
        return vertx.eventBus().<JsonObject>request("llm.generate.character", request,
                        new DeliveryOptions().setSendTimeout(600_000))
                .map(reply -> {
                    JsonArray roster = reply.body().getJsonArray("characters");
                    if (roster == null || roster.size() != count) throw new IllegalArgumentException("The generated roster has the wrong number of characters.");
                    List<CharacterDraft> drafts = new ArrayList<>();
                    for (int i = 0; i < count; i++) drafts.add(draft(roster.getJsonObject(i)));
                    return drafts;
                }).recover(error -> {
                    String message = Objects.toString(error.getMessage(), "");
                    String prefix = "Failed to generate roster: ";
                    int index = message.indexOf(prefix);
                    String friendly = index >= 0 ? message.substring(index + prefix.length())
                            : "The model could not finish the roster. Please try again or use a shorter setting.";
                    return io.vertx.core.Future.failedFuture(new GraphQLException(friendly));
                }).toCompletionStage().toCompletableFuture();
    }

    /** Builds a reviewable draft, including the alignment and intra-roster feelings. */
    static CharacterDraft draft(JsonObject member) {
        String alignment = AlignmentRules.named(member.getString("alignment"));
        if (alignment == null) alignment = "TRUE_NEUTRAL";
        int[] points = AlignmentRules.pointsFor(alignment);
        CharacterDraft draft = new CharacterDraft();
        draft.setCharacter(character(member, null));
        draft.setAlignment(alignment);
        draft.setLawfulness(points[0]);
        draft.setGoodness(points[1]);
        List<CharacterDraftRelationship> relationships = new ArrayList<>();
        JsonArray generated = member.getJsonArray("relationships");
        if (generated != null) {
            for (Object value : generated) {
                if (!(value instanceof JsonObject relation)) continue;
                String target = relation.getString("targetName");
                if (target == null || target.isBlank()) continue;
                // Neutral connections stay in the draft so reviewers can see and
                // adjust them. Saving skips neutral rows because they carry no ledger value.
                String tier = AffinityRules.tierName(relation.getString("feeling"));
                relationships.add(new CharacterDraftRelationship(target, tier,
                        Objects.toString(relation.getString("description"), "")));
            }
        }
        draft.setRelationships(relationships);
        return draft;
    }

    @Mutation
    public @NonNull CompletableFuture<List<@NonNull Character>> createCharacterRoster(
            @Name("inputs") @NonNull List<@NonNull RosterMemberInput> inputs,
            @Name("groupName") String groupName,
            @Name("setting") String setting) throws GraphQLException {
        var auth = graphQLAuthContext.requireAuth();
        if (inputs == null || inputs.size() < 2 || inputs.size() > 12)
            throw new GraphQLException("A roster must contain between 2 and 12 characters.");
        UUID owner = auth.getUserId();
        Set<String> names = new HashSet<>();
        List<Character> characters = new ArrayList<>();
        List<Tuple> rows = new ArrayList<>();
        List<int[]> alignments = new ArrayList<>();
        Map<String, Character> byName = new LinkedHashMap<>();
        List<JsonObject> feelings = new ArrayList<>();

        for (RosterMemberInput member : inputs) {
            if (member == null || member.getCharacter() == null)
                throw new GraphQLException("Every roster member needs character details.");
            var input = member.getCharacter();
            if (input.getName() == null || input.getName().isBlank()
                    || input.getSystemPrompt() == null || input.getSystemPrompt().isBlank())
                throw new GraphQLException("Every roster member needs a name and character instructions.");
            String name = input.getName().strip();
            if (name.codePointCount(0, name.length()) > 255 || !names.add(name.toLowerCase(Locale.ROOT)))
                throw new GraphQLException("Roster names must be unique and no longer than 255 characters.");
            JsonObject data = JsonObject.mapFrom(input).put("name", name);
            for (String field : List.of("description", "systemPrompt", "appearance", "personality", "likes", "dislikes", "backstory", "goals", "greeting", "examples", "avatar")) {
                String text = data.getString(field);
                if (text != null && text.getBytes(StandardCharsets.UTF_8).length > 60000)
                    throw new GraphQLException("Character text is too long to save.");
            }
            int[] points = alignment(input.getLawfulness(), input.getGoodness());
            Character character = character(data, owner);
            characters.add(character);
            byName.put(name.toLowerCase(Locale.ROOT), character);
            alignments.add(points);
            rows.add(Tuple.tuple().addString(character.getId().toString()).addString(character.getName())
                    .addString(character.getDescription()).addString(character.getSystemPrompt()).addString(character.getAppearance())
                    .addString(character.getPersonality())
                    .addString(character.getGender()).addString(character.getPronouns())
                    .addValue(character.getAgeYears()).addString(character.getHeight())
                    .addString(character.getBuild()).addString(character.getHair()).addString(character.getEyes())
                    .addValue(character.getStrength()).addValue(character.getDexterity())
                    .addValue(character.getConstitution()).addValue(character.getIntelligence())
                    .addValue(character.getWisdom()).addValue(character.getCharisma())
                    .addString(character.getLikes()).addString(character.getDislikes())
                    .addString(character.getBackstory()).addString(character.getGoals()).addString(character.getGreeting())
                    .addString(character.getExamples()).addString(character.getAvatar()).addBoolean(false).addBoolean(false)
                    .addDouble(character.getTemperature()).addInteger(character.getMaxTokens()).addDouble(character.getTopP())
                    .addInteger(character.getTopK()).addDouble(character.getRepeatPenalty()).addString("roleplay")
                    .addString(new JsonArray(character.getTags()).encode()).addString(owner.toString())
                    .addLocalDateTime(LocalDateTime.parse(character.getCreatedAt())).addLocalDateTime(LocalDateTime.parse(character.getUpdatedAt())));
            if (member.getRelationships() != null) {
                for (RosterRelationshipInput relationship : member.getRelationships()) {
                    if (relationship == null || relationship.getTargetName() == null) continue;
                    String target = relationship.getTargetName().strip().toLowerCase(Locale.ROOT);
                    if (target.equals(name.toLowerCase(Locale.ROOT)))
                        throw new GraphQLException("A character cannot feel something toward itself.");
                    String tier = AffinityRules.tierName(relationship.getTier());
                    if (tier.equals("NEUTRAL")) continue;
                    feelings.add(new JsonObject().put("from", name.toLowerCase(Locale.ROOT))
                            .put("to", target).put("tier", tier));
                }
            }
        }
        for (JsonObject feeling : feelings) {
            if (!byName.containsKey(feeling.getString("to")))
                throw new GraphQLException("Every generated relationship must name a member of this roster.");
        }

        String sql = """
            INSERT INTO `character` (characterUUID, name, description, systemPrompt, appearance,
              personality, gender, pronouns, ageYears, height, build, hair, eyes,
              strength, dexterity, constitution, intelligence, wisdom, charisma,
              likes, dislikes, backstory, goals, greeting, examples, avatar, isDefault, isPublic,
              temperature, maxTokens, topP, topK, repeatPenalty, category, tags, ownerId, createdAt, updatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        // New IDs only. A failed insert rolls back every member of the roster.
        return mysqlPool.withTransaction(connection -> connection.preparedQuery(sql).executeBatch(rows)
                        .compose(ignored -> saveAlignments(connection, owner, characters, alignments))
                        .compose(ignored -> saveFeelings(connection, owner, byName, feelings))
                        .compose(ignored -> saveGroup(connection, owner, groupName, setting, characters))
                        .map(characters))
                .map(saved -> {
                    vertx.eventBus().publish("user:" + auth.getUserId() + ":characters:updated", new JsonObject().put("rosterSize", saved.size()));
                    return saved;
                }).toCompletionStage().toCompletableFuture();
    }

    private static int[] alignment(Integer lawfulness, Integer goodness) throws GraphQLException {
        int law = lawfulness == null ? 0 : lawfulness;
        int good = goodness == null ? 0 : goodness;
        try {
            AlignmentRules.validatePoints(law);
            AlignmentRules.validatePoints(good);
        } catch (IllegalArgumentException error) { throw new GraphQLException(error.getMessage()); }
        return new int[]{law, good};
    }

    private static io.vertx.core.Future<Void> saveAlignments(io.vertx.sqlclient.SqlConnection connection,
                                                             UUID owner, List<Character> characters, List<int[]> alignments) {
        List<Tuple> rows = new ArrayList<>();
        for (int i = 0; i < characters.size(); i++) {
            int[] points = alignments.get(i);
            if (points[0] == 0 && points[1] == 0) continue;
            String id = characters.get(i).getId().toString();
            rows.add(Tuple.of(UUID.randomUUID().toString(), owner.toString(), id, id, points[0], points[1]));
        }
        if (rows.isEmpty()) return io.vertx.core.Future.succeededFuture();
        return connection.preparedQuery("""
            INSERT INTO character_alignment (alignmentId, ownerId, characterId, actor, lawfulness, goodness)
            VALUES (?, ?, ?, ?, ?, ?)
            """).executeBatch(rows).mapEmpty();
    }

    private static io.vertx.core.Future<Void> saveFeelings(io.vertx.sqlclient.SqlConnection connection,
                                                           UUID owner, Map<String, Character> byName,
                                                           List<JsonObject> feelings) {
        if (feelings.isEmpty()) return io.vertx.core.Future.succeededFuture();
        List<Tuple> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonObject feeling : feelings) {
            String from = feeling.getString("from"), to = feeling.getString("to");
            if (!seen.add(from + ">" + to)) continue;
            String fromId = byName.get(from).getId().toString(), toId = byName.get(to).getId().toString();
            rows.add(Tuple.of(UUID.randomUUID().toString(), owner.toString(), fromId, toId, fromId, toId,
                    AffinityRules.pointsForTier(feeling.getString("tier"))));
        }
        return connection.preparedQuery("""
            INSERT INTO character_affinity
              (affinityId, ownerId, fromCharacterId, toCharacterId, fromActor, toActor, points)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE points = VALUES(points)
            """).executeBatch(rows).mapEmpty();
    }

    /** Records the roster and its ordered members in the same transaction. */
    private static io.vertx.core.Future<Void> saveGroup(io.vertx.sqlclient.SqlConnection connection,
                                                        UUID owner, String requestedName, String setting,
                                                        List<Character> characters) {
        String name = rosterName(requestedName, setting, characters);
        String groupId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return connection.preparedQuery("""
                INSERT INTO character_group (groupUUID, ownerId, name, description, setting, createdAt, updatedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).execute(Tuple.tuple()
                        .addValue(groupId).addValue(owner.toString()).addValue(name)
                        .addValue(setting).addValue(setting).addValue(now).addValue(now))
                .compose(ignored -> {
                    List<Tuple> members = new ArrayList<>();
                    for (int i = 0; i < characters.size(); i++) {
                        members.add(Tuple.of(groupId, characters.get(i).getId().toString(), i, now));
                    }
                    return connection.preparedQuery("""
                            INSERT INTO character_group_member (groupId, characterId, position, createdAt)
                            VALUES (?, ?, ?, ?)
                            """).executeBatch(members);
                })
                .mapEmpty();
    }

    private static final int ROSTER_NAME_LIMIT = 40;

    /**
     * A roster name must identify a cast in a list. A long setting is replaced
     * by the first member and the remaining count, so the label stays short
     * and distinguishable.
     */
    static String rosterName(String requested, String setting, List<Character> characters) {
        if (requested != null && !requested.isBlank()) return truncate(requested.strip(), 255);
        if (setting != null && !setting.isBlank()) {
            String first = setting.strip().split("\\R|[.!?]", 2)[0].strip();
            if (!first.isEmpty() && first.length() <= ROSTER_NAME_LIMIT) return first;
        }
        if (!characters.isEmpty()) {
            String lead = characters.get(0).getName();
            int others = characters.size() - 1;
            return truncate(others > 0 ? lead + " and " + others + " other" + (others == 1 ? "" : "s") : lead, 255);
        }
        if (setting != null && !setting.isBlank()) {
            return truncate(setting.strip(), ROSTER_NAME_LIMIT);
        }
        return "New roster";
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).strip();
    }

    private static Character character(JsonObject data, UUID ownerId) {
        String now = LocalDateTime.now(ZoneOffset.UTC).toString();
        return Character.builder().id(UUID.randomUUID()).ownerId(ownerId)
                .name(data.getString("name")).description(text(data, "description")).systemPrompt(text(data, "systemPrompt"))
                .personality(text(data, "personality")).appearance(text(data, "appearance"))
                .gender(text(data, "gender")).pronouns(text(data, "pronouns"))
                .ageYears(optionalInteger(data, "ageYears", 0, 1_000_000))
                .height(text(data, "height")).build(text(data, "build"))
                .hair(text(data, "hair")).eyes(text(data, "eyes"))
                .strength(attribute(data, "strength")).dexterity(attribute(data, "dexterity"))
                .constitution(attribute(data, "constitution")).intelligence(attribute(data, "intelligence"))
                .wisdom(attribute(data, "wisdom")).charisma(attribute(data, "charisma"))
                .backstory(text(data, "backstory")).goals(text(data, "goals"))
                .likes(text(data, "likes")).dislikes(text(data, "dislikes")).greeting(text(data, "greeting"))
                .examples(text(data, "examples")).avatar(text(data, "avatar"))
                .temperature(0.7).topP(0.9).topK(40).maxTokens(-1).repeatPenalty(1.0)
                .category("roleplay").tags(List.of("roster")).isDefault(false).isPublic(false).isFavorite(false)
                .createdAt(now).updatedAt(now).build();
    }

    /** Reads a JSON number or numeric string inside the allowed range. */
    private static Integer optionalInteger(JsonObject data, String field, int min, int max) {
        Object raw = data.getValue(field);
        if (raw == null) return null;
        Integer value = null;
        if (raw instanceof Number number) value = number.intValue();
        else if (raw instanceof String text && text.strip().matches("\\d+")) value = Integer.parseInt(text.strip());
        if (value == null) return null;
        return Math.max(min, Math.min(max, value));
    }

    private static Integer attribute(JsonObject data, String field) {
        return optionalInteger(data, field, 1, 20);
    }

    private static String text(JsonObject data, String field) { return Objects.toString(data.getString(field), ""); }
}
