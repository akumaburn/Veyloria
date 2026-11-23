package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.StoryTemplate;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class StoryTemplateService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<List<StoryTemplate>> getTemplates(String genre) {
        String query;
        Tuple params;
        if (genre != null && !genre.isEmpty()) {
            query = "SELECT * FROM story_template WHERE isPublic = TRUE AND genre = ? ORDER BY name";
            params = Tuple.of(genre);
        } else {
            query = "SELECT * FROM story_template WHERE isPublic = TRUE ORDER BY name";
            params = Tuple.tuple();
        }
        return pool.preparedQuery(query)
            .execute(params)
            .map(rows -> {
                List<StoryTemplate> templates = new ArrayList<>();
                for (Row row : rows) {
                    templates.add(mapRowToTemplate(row));
                }
                return templates;
            });
    }

    public Uni<StoryTemplate> getTemplate(String templateId) {
        String query = "SELECT * FROM story_template WHERE templateId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(templateId))
            .map(rows -> {
                if (rows.size() == 0) return null;
                return mapRowToTemplate(rows.iterator().next());
            });
    }

    public Uni<StoryTemplate> createTemplate(String name, String genre, String description,
                                                  JsonObject defaultWorldState, JsonObject rules,
                                                  String ownerId) {
        String id = UUID.randomUUID().toString();
        String insert = """
            INSERT INTO story_template (templateId, name, genre, description, defaultWorldState, rules, ownerId)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        return pool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(name).addValue(genre).addValue(description)
                .addValue(defaultWorldState != null ? defaultWorldState.encode() : null)
                .addValue(rules != null ? rules.encode() : null)
                .addValue(ownerId))
            .chain(v -> getTemplate(id));
    }

    private StoryTemplate mapRowToTemplate(Row row) {
        StoryTemplate t = new StoryTemplate();
        t.setTemplateId(row.getString("templateId"));
        t.setName(row.getString("name"));
        t.setGenre(row.getString("genre"));
        t.setDescription(row.getString("description"));
        t.setDefaultWorldState(ServiceHelper.safeGetJsonObject(row, "defaultWorldState"));
        t.setSuggestedCharacterArchetypes(ServiceHelper.safeGetJsonObject(row, "suggestedCharacterArchetypes"));
        t.setRules(ServiceHelper.safeGetJsonObject(row, "rules"));
        t.setMoodPresets(ServiceHelper.safeGetJsonObject(row, "moodPresets"));
        t.setIsBuiltIn(row.getBoolean("isBuiltIn"));
        t.setIsPublic(row.getBoolean("isPublic"));
        if (row.getString("ownerId") != null) t.setOwnerId(UUID.fromString(row.getString("ownerId")));
        t.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        t.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return t;
    }
}
