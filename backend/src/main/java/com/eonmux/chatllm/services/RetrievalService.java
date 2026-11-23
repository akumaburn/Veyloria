package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.models.KnowledgeEntry;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@Slf4j
public class RetrievalService {

    private static final Set<String> STOPWORDS = Set.of(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "is", "it", "as", "was", "are", "be",
        "has", "had", "have", "this", "that", "these", "those", "not", "no",
        "will", "can", "do", "did", "does", "been", "would", "could", "should",
        "may", "might", "shall", "its", "his", "her", "their", "our", "your",
        "my", "me", "him", "them", "she", "he", "we", "you", "they", "who",
        "what", "which", "when", "where", "how", "all", "each", "every",
        "both", "few", "more", "most", "some", "any", "just", "about", "into",
        "than", "then", "also", "very", "too", "here", "there", "said", "says"
    );

    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    AppConfig config;
    @Inject
    KnowledgeService knowledgeService;

    // ============================================================
    // A) Knowledge Promotion
    // ============================================================

    /**
     * Promote high-importance Highlighter entries to persistent knowledge_entry records.
     */
    public Uni<Integer> promoteFromHighlighter(String conversationId, JsonArray highlighterEntries,
                                                   String sourceMessageId, Integer highlighterVersion) {
        if (!config.isRagPromotionEnabled()) {
            return Uni.createFrom().item(0);
        }
        if (highlighterEntries == null || highlighterEntries.isEmpty()) {
            return Uni.createFrom().item(0);
        }

        float threshold = config.getRagPromotionThreshold();

        // Filter entries by importance threshold
        List<JsonObject> candidates = new ArrayList<>();
        for (int i = 0; i < highlighterEntries.size(); i++) {
            JsonObject entry = highlighterEntries.getJsonObject(i);
            float importance = entry.getFloat("importance", 0.5f);
            if (importance >= threshold) {
                candidates.add(entry);
            }
        }

        if (candidates.isEmpty()) {
            return Uni.createFrom().item(0);
        }

        // Compute hashes for dedup
        Map<String, JsonObject> hashToEntry = new LinkedHashMap<>();
        for (JsonObject candidate : candidates) {
            String summary = candidate.getString("summary", "");
            String hash = computeHash(normalizeSummary(summary));
            hashToEntry.put(hash, candidate);
        }

        // Batch-check existing hashes
        return checkExistingHashes(conversationId, hashToEntry.keySet())
            .chain(existingHashes -> {
                // Filter out already-existing entries
                List<Map.Entry<String, JsonObject>> newEntries = hashToEntry.entrySet().stream()
                    .filter(e -> !existingHashes.contains(e.getKey()))
                    .collect(Collectors.toList());

                if (newEntries.isEmpty()) {
                    return Uni.createFrom().item(0);
                }

                // Insert new entries
                List<Uni<Void>> insertFutures = new ArrayList<>();
                for (Map.Entry<String, JsonObject> entry : newEntries) {
                    String hash = entry.getKey();
                    JsonObject hlEntry = entry.getValue();
                    insertFutures.add(insertPromotedEntry(conversationId, hlEntry, sourceMessageId,
                        highlighterVersion, hash));
                }

                return Uni.join().all(insertFutures).andCollectFailures()
                    .map(results -> {
                        int count = newEntries.size();
                        log.info("[rag] Promoted {} knowledge entries for conversation {}",
                            count, conversationId);
                        return count;
                    });
            });
    }

    private Uni<Set<String>> checkExistingHashes(String conversationId, Set<String> hashes) {
        if (hashes.isEmpty()) {
            return Uni.createFrom().item(Collections.emptySet());
        }

        // Build IN clause with placeholders
        String placeholders = hashes.stream().map(h -> "?").collect(Collectors.joining(","));
        String sql = "SELECT summaryHash FROM knowledge_entry WHERE conversationId = ? " +
            "AND isActive = TRUE AND summaryHash IN (" + placeholders + ")";

        List<Object> params = new ArrayList<>();
        params.add(conversationId);
        params.addAll(hashes);

        Tuple tuple = Tuple.tuple();
        for (Object param : params) {
            tuple.addValue(param);
        }

        return pool.preparedQuery(sql)
            .execute(tuple)
            .map(rows -> {
                Set<String> existing = new HashSet<>();
                for (Row row : rows) {
                    String hash = row.getString("summaryHash");
                    if (hash != null) existing.add(hash);
                }
                return existing;
            });
    }

    private Uni<Void> insertPromotedEntry(String conversationId, JsonObject hlEntry,
                                              String sourceMessageId, Integer highlighterVersion,
                                              String hash) {
        String id = UUID.randomUUID().toString();
        String summary = hlEntry.getString("summary", "");
        float importance = hlEntry.getFloat("importance", 0.5f);

        // Extract tags
        String tags = "";
        JsonArray tagArray = hlEntry.getJsonArray("tags");
        if (tagArray != null && !tagArray.isEmpty()) {
            tags = tagArray.stream()
                .map(Object::toString)
                .collect(Collectors.joining(","));
        }

        // Extract visibility
        Object vis = hlEntry.getValue("visibility");
        String visJson;
        if (vis instanceof JsonArray) {
            visJson = ((JsonArray) vis).encode();
        } else if (vis instanceof String) {
            visJson = "\"" + vis + "\"";
        } else {
            visJson = "\"ALL\"";
        }

        String sql = """
            INSERT INTO knowledge_entry (entryId, conversationId, summary, visibility,
                importance, sourceMessageId, tags, sourceHighlighterVersion, summaryHash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(summary).addValue(visJson)
                .addValue((double) importance).addValue(sourceMessageId).addValue(tags)
                .addValue(highlighterVersion).addValue(hash))
            .replaceWithVoid();
    }

    // ============================================================
    // B) Relevance Retrieval
    // ============================================================

    /**
     * Retrieve knowledge entries relevant to the query.
     */
    public Uni<List<ScoredEntry>> retrieve(String conversationId, String queryText,
                                               List<String> queryTags, String characterId,
                                               int maxEntries) {
        List<String> keywords = extractKeywords(queryText);
        String searchQuery = String.join(" ", keywords);

        if (searchQuery.isBlank() && (queryTags == null || queryTags.isEmpty())) {
            // Fallback: return highest importance entries
            return getHighImportanceEntries(conversationId, characterId, maxEntries);
        }

        return executeFulltextQuery(conversationId, searchQuery, characterId, maxEntries * 3)
            .map(candidatesWithScores -> {
                // Score and rank
                List<ScoredEntry> scored = scoreEntries(candidatesWithScores, queryTags);

                // Apply token budget
                return applyTokenBudget(scored, config.getRagMaxContextTokens());
            });
    }

    private Uni<List<CandidateEntry>> executeFulltextQuery(String conversationId,
                                                                String searchQuery,
                                                                String characterId,
                                                                int limit) {
        String sql;
        Tuple params;

        if (!searchQuery.isBlank()) {
            sql = """
                SELECT ke.*, MATCH(ke.summary) AGAINST(? IN NATURAL LANGUAGE MODE) AS ft_score
                FROM knowledge_entry ke
                WHERE ke.conversationId = ? AND ke.isActive = TRUE
                  AND (MATCH(ke.summary) AGAINST(? IN NATURAL LANGUAGE MODE) > 0 OR ke.importance >= 0.8)
                ORDER BY ft_score DESC LIMIT ?
                """;
            params = Tuple.of(searchQuery, conversationId, searchQuery, limit);
        } else {
            // No search query, just get high-importance entries
            sql = """
                SELECT ke.*, 0 AS ft_score
                FROM knowledge_entry ke
                WHERE ke.conversationId = ? AND ke.isActive = TRUE AND ke.importance >= 0.8
                ORDER BY ke.importance DESC LIMIT ?
                """;
            params = Tuple.of(conversationId, limit);
        }

        return pool.preparedQuery(sql)
            .execute(params)
            .map(rows -> {
                List<CandidateEntry> candidates = new ArrayList<>();
                for (Row row : rows) {
                    KnowledgeEntry entry = mapRowToEntry(row);

                    // Filter by character visibility
                    if (characterId != null && !entry.isVisibleTo(characterId)) {
                        continue;
                    }

                    double ftScore = row.getDouble("ft_score");
                    candidates.add(new CandidateEntry(entry, ftScore));
                }
                return candidates;
            });
    }

    private Uni<List<ScoredEntry>> getHighImportanceEntries(String conversationId,
                                                                 String characterId,
                                                                 int maxEntries) {
        String sql = """
            SELECT ke.*, 0 AS ft_score FROM knowledge_entry ke
            WHERE ke.conversationId = ? AND ke.isActive = TRUE AND ke.importance >= 0.7
            ORDER BY ke.importance DESC, ke.createdAt DESC LIMIT ?
            """;
        return pool.preparedQuery(sql)
            .execute(Tuple.of(conversationId, maxEntries))
            .map(rows -> {
                List<ScoredEntry> entries = new ArrayList<>();
                for (Row row : rows) {
                    KnowledgeEntry entry = mapRowToEntry(row);
                    if (characterId != null && !entry.isVisibleTo(characterId)) continue;
                    entries.add(new ScoredEntry(entry, entry.getImportance()));
                }
                return applyTokenBudget(entries, config.getRagMaxContextTokens());
            });
    }

    private List<ScoredEntry> scoreEntries(List<CandidateEntry> candidates, List<String> queryTags) {
        if (candidates.isEmpty()) return Collections.emptyList();

        // Normalize fulltext scores
        double maxFt = candidates.stream()
            .mapToDouble(c -> c.ftScore)
            .max()
            .orElse(1.0);
        if (maxFt <= 0) maxFt = 1.0;

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        float wFulltext = config.getRagFulltextWeight();
        float wTag = config.getRagTagMatchWeight();
        float wImportance = config.getRagImportanceWeight();
        float wRecency = config.getRagRecencyWeight();
        float decayRate = config.getRagRecencyDecayRate();

        Set<String> queryTagSet = (queryTags != null)
            ? new HashSet<>(queryTags)
            : Collections.emptySet();

        List<ScoredEntry> scored = new ArrayList<>();
        for (CandidateEntry candidate : candidates) {
            KnowledgeEntry entry = candidate.entry;

            // 1. Fulltext relevance (0-1)
            double ftNormalized = candidate.ftScore / maxFt;

            // 2. Tag overlap (0-1)
            double tagScore = 0;
            if (!queryTagSet.isEmpty() && entry.getTags() != null && !entry.getTags().isEmpty()) {
                long matchCount = entry.getTags().stream()
                    .filter(t -> queryTagSet.contains(t.trim().toLowerCase()))
                    .count();
                tagScore = (double) matchCount / queryTagSet.size();
            }

            // 3. Importance (0-1)
            double importance = entry.getImportance() != null ? entry.getImportance() : 0.5;

            // 4. Recency (0-1, exponential decay)
            double daysSinceCreated = 0;
            if (entry.getCreatedAt() != null) {
                daysSinceCreated = ChronoUnit.HOURS.between(LocalDateTime.parse(entry.getCreatedAt()), now) / 24.0;
            }
            double recency = Math.exp(-decayRate * daysSinceCreated);

            // Composite score
            double compositeScore = wFulltext * ftNormalized
                + wTag * tagScore
                + wImportance * importance
                + wRecency * recency;

            scored.add(new ScoredEntry(entry, compositeScore));
        }

        // Sort by score descending
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        return scored;
    }

    private List<ScoredEntry> applyTokenBudget(List<ScoredEntry> scored, int maxTokens) {
        List<ScoredEntry> result = new ArrayList<>();
        int accumulatedTokens = 0;

        for (ScoredEntry entry : scored) {
            String summary = entry.entry.getSummary();
            int estimatedTokens = (summary != null) ? summary.length() / 4 : 0;

            if (accumulatedTokens + estimatedTokens > maxTokens && !result.isEmpty()) {
                break;
            }
            accumulatedTokens += estimatedTokens;
            result.add(entry);

            if (result.size() >= config.getRagMaxRetrievedEntries()) {
                break;
            }
        }

        return result;
    }

    // ============================================================
    // C) Context Formatting
    // ============================================================

    /**
     * Build a formatted context string from scored entries.
     */
    public String buildContextString(List<ScoredEntry> entries) {
        if (entries == null || entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("=== RELEVANT STORY KNOWLEDGE ===\n");
        for (ScoredEntry scored : entries) {
            KnowledgeEntry entry = scored.entry;
            sb.append("- ").append(entry.getSummary());
            if (entry.getTags() != null && !entry.getTags().isEmpty()) {
                sb.append(" [").append(String.join(", ", entry.getTags())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ============================================================
    // Keyword extraction (Java-only, no LLM)
    // ============================================================

    List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        String[] tokens = text.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .split("\\s+");

        return Arrays.stream(tokens)
            .filter(t -> t.length() >= 3)
            .filter(t -> !STOPWORDS.contains(t))
            .distinct()
            .limit(20)
            .collect(Collectors.toList());
    }

    // ============================================================
    // Helpers
    // ============================================================

    String normalizeSummary(String summary) {
        if (summary == null) return "";
        return summary.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    String computeHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("SHA-256 not available, using hashCode fallback", e);
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * Get the current highlighter cache version for a conversation.
     */
    public Uni<Integer> getHighlighterVersion(String conversationId) {
        String sql = "SELECT version FROM highlighter_cache WHERE conversationId = ?";
        return pool.preparedQuery(sql)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                for (Row row : rows) {
                    return row.getInteger("version");
                }
                return 0;
            });
    }

    private KnowledgeEntry mapRowToEntry(Row row) {
        KnowledgeEntry e = new KnowledgeEntry();
        e.setEntryId(UUID.fromString(row.getString("entryId")));
        e.setConversationId(UUID.fromString(row.getString("conversationId")));
        e.setSummary(row.getString("summary"));
        try {
            Object vis = row.get(Object.class, row.getColumnIndex("visibility"));
            if (vis instanceof String s) {
                if (s.startsWith("[")) e.setVisibility(new JsonArray(s));
                else e.setVisibility(s.replace("\"", ""));
            } else {
                e.setVisibility(vis);
            }
        } catch (Exception ex) {
            e.setVisibility("ALL");
        }
        if (row.getString("sceneId") != null) e.setSceneId(UUID.fromString(row.getString("sceneId")));
        e.setImportance(row.getDouble("importance"));
        if (row.getString("sourceMessageId") != null) e.setSourceMessageId(UUID.fromString(row.getString("sourceMessageId")));
        e.setDayNumber(row.getInteger("dayNumber"));
        if (row.getString("tags") != null) e.setTagsFromString(row.getString("tags"));
        e.setIsActive(row.getBoolean("isActive"));
        try {
            e.setSourceHighlighterVersion(row.getInteger("sourceHighlighterVersion"));
        } catch (Exception ex) { /* column may be null */ }
        try {
            e.setSummaryHash(row.getString("summaryHash"));
        } catch (Exception ex) { /* column may be null */ }
        e.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        return e;
    }

    // ============================================================
    // Inner types
    // ============================================================

    record CandidateEntry(KnowledgeEntry entry, double ftScore) {}

    public record ScoredEntry(KnowledgeEntry entry, double score) {}
}
