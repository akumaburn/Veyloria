package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.KnowledgeEntry;
import com.eonmux.chatllm.models.StoryTemplate;
import com.eonmux.chatllm.models.WorldState;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.WorldStateService;
import com.eonmux.chatllm.services.auth.AuthContext;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Name;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GraphQL resolver for roleplay world state operations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class WorldStateResolver {

    @Inject
    Vertx vertx;

    @Inject
    Pool mysqlPool;

    @Inject
    WorldStateService worldStateService;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    /**
     * Get world state for a conversation.
     * Schema: worldState(conversationId: ID!): WorldState
     */
    @Query
    public Uni<WorldState> worldState(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String verifyQuery = "SELECT COUNT(*) as count FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        return mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(conversationId, auth.getUserId().toString()))
            .chain(verifyRows -> {
                Long count = verifyRows.iterator().next().getLong("count");
                if (count == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or unauthorized"));
                }
                return worldStateService.getOrCreateWorldState(conversationId);
            });
    }

    /**
     * List story templates with optional genre filter.
     * Schema: storyTemplates(genre: String): [StoryTemplate!]!
     */
    @Query
    public Uni<List<StoryTemplate>> storyTemplates(@Name("genre") String genre) {
        // Build the WHERE clause conditionally: the previous `? IS NULL` idiom
        // throws with Vert.x prepared statements when genre is null (untyped
        // nulls NPE in Tuple), so a caller omitting the optional filter would
        // crash the query instead of listing all public templates.
        graphQLAuthContext.requireAuth();
        String query;
        Tuple tuple;
        if (genre != null && !genre.isEmpty()) {
            query = "SELECT * FROM story_template WHERE genre = ? AND isPublic = TRUE ORDER BY name";
            tuple = Tuple.of(genre);
        } else {
            query = "SELECT * FROM story_template WHERE isPublic = TRUE ORDER BY name";
            tuple = Tuple.tuple();
        }
        return mysqlPool.preparedQuery(query)
            .execute(tuple)
            .map(rows -> {
                List<StoryTemplate> templates = new ArrayList<>();
                rows.forEach(row -> {
                    StoryTemplate template = StoryTemplate.builder()
                        .templateId(row.getString("templateId"))
                        .name(row.getString("name"))
                        .genre(row.getString("genre"))
                        .description(row.getString("description"))
                        .defaultWorldState(ServiceHelper.safeGetJsonObject(row, "defaultWorldState"))
                        .suggestedCharacterArchetypes(ServiceHelper.safeGetJsonObject(row, "suggestedCharacterArchetypes"))
                        .rules(ServiceHelper.safeGetJsonObject(row, "rules"))
                        .moodPresets(ServiceHelper.safeGetJsonObject(row, "moodPresets"))
                        .isBuiltIn(row.getBoolean("isBuiltIn"))
                        .isPublic(row.getBoolean("isPublic"))
                        .ownerId(row.getString("ownerId") != null ? UUID.fromString(row.getString("ownerId")) : null)
                        .build();
                    templates.add(template);
                });
                return templates;
            });
    }

    /**
     * Update world state for a conversation.
     * Schema: updateWorldState(conversationId: ID!, input: UpdateWorldStateInput!): WorldState!
     */
    @Mutation
    public Uni<WorldState> updateWorldState(@Name("conversationId") String conversationId, @Name("input") UpdateWorldStateInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        // Verify user has access to this conversation
        String verifyQuery = "SELECT COUNT(*) as count FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        return mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(conversationId, auth.getUserId().toString()))
            .chain(verifyRows -> {
                Long count = verifyRows.iterator().next().getLong("count");
                if (count == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or unauthorized"));
                }

                JsonObject updates = new JsonObject(com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext));
                return worldStateService.updateWorldState(conversationId, updates);
            });
    }

    /**
     * Initialize world state from a template.
     * Schema: initializeFromTemplate(conversationId: ID!, templateId: ID!): WorldState!
     */
    @Mutation
    public Uni<WorldState> initializeFromTemplate(@Name("conversationId") String conversationId, @Name("templateId") String templateId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        // Verify user has access to this conversation
        String verifyQuery = "SELECT COUNT(*) as count FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        return mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(conversationId, auth.getUserId().toString()))
            .chain(verifyRows -> {
                Long count = verifyRows.iterator().next().getLong("count");
                if (count == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or unauthorized"));
                }

                return worldStateService.initializeFromTemplate(conversationId, templateId);
            });
    }

    /**
     * Get knowledge entries for a conversation.
     * Schema: knowledgeEntries(conversationId: ID!, characterId: ID): [KnowledgeEntry!]!
     */
    @Query
    public Uni<List<KnowledgeEntry>> knowledgeEntries(@Name("conversationId") String conversationId, @Name("characterId") String characterId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        // Verify user has access to this conversation
        String verifyQuery = "SELECT COUNT(*) as count FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        return mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(conversationId, auth.getUserId().toString()))
            .chain(verifyRows -> {
                Long count = verifyRows.iterator().next().getLong("count");
                if (count == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or unauthorized"));
                }

                String query;
                Tuple params;
                if (characterId != null && !characterId.isEmpty()) {
                    // Filter by visibility - check if character is in the visibility JSON array or if "ALL" is present
                    query = """
                        SELECT * FROM knowledge_entry
                        WHERE conversationId = ? AND isActive = TRUE
                        AND (JSON_CONTAINS(visibility, ?, '$') OR JSON_CONTAINS(visibility, '"ALL"', '$'))
                        ORDER BY createdAt DESC
                        """;
                    params = Tuple.of(conversationId, "\"" + characterId + "\"");
                } else {
                    query = "SELECT * FROM knowledge_entry WHERE conversationId = ? AND isActive = TRUE ORDER BY createdAt DESC";
                    params = Tuple.of(conversationId);
                }

                return mysqlPool.preparedQuery(query)
                    .execute(params)
                    .map(rows -> {
                        List<KnowledgeEntry> entries = new ArrayList<>();
                        rows.forEach(row -> {
                            // Parse tags: stored as JSON array string or comma-separated
                            String tagsStr = row.getString("tags");
                            List<String> tags;
                            if (tagsStr != null && !tagsStr.isEmpty()) {
                                try {
                                    tags = new io.vertx.core.json.JsonArray(tagsStr).getList();
                                } catch (Exception e2) {
                                    // Fallback: treat as comma-separated
                                    tags = Arrays.asList(tagsStr.split(","));
                                }
                            } else {
                                tags = List.of();
                            }

                            // Parse visibility
                            Object visibility;
                            JsonObject vis = ServiceHelper.safeGetJsonObject(row, "visibility");
                            visibility = vis != null ? vis : "ALL";

                            KnowledgeEntry entry = KnowledgeEntry.builder()
                                .entryId(row.getString("entryId") != null ? java.util.UUID.fromString(row.getString("entryId")) : null)
                                .conversationId(row.getString("conversationId") != null ? java.util.UUID.fromString(row.getString("conversationId")) : null)
                                .summary(row.getString("summary"))
                                .visibility(visibility)
                                .importance(row.getDouble("importance"))
                                .dayNumber(row.getInteger("dayNumber"))
                                .tags(tags)
                                .isActive(row.getBoolean("isActive"))
                                .build();
                            entries.add(entry);
                        });
                        return entries;
                    });
            });
    }
}
