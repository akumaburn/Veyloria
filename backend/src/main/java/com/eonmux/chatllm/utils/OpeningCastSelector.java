package com.eonmux.chatllm.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses the small cast that opens a scene.
 *
 * <p>The opening group must feel like people who would plausibly be together
 * for this scenario, not simply the first names in the roster. The selector
 * weighs three signals:
 * <ul>
 *   <li>how strongly each person matches the scenario's language and role,</li>
 *   <li>how strongly the group is connected by recorded feelings, and</li>
 *   <li>whether the group brings different kinds of expertise to the scene.</li>
 * </ul>
 *
 * <p>The result is deterministic. When the cast carries no useful signal at
 * all, roster order is the final tie-break so the same input always produces
 * the same opening.
 */
public final class OpeningCastSelector {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}']+");
    private static final Set<String> STOP_WORDS = Set.copyOf(List.of(
            "the", "and", "a", "an", "of", "in", "on", "at", "to", "for", "with", "from", "by",
            "as", "is", "are", "was", "were", "be", "been", "being", "that", "this", "these",
            "those", "it", "its", "his", "her", "their", "they", "he", "she", "you", "your", "we",
            "our", "not", "but", "or", "if", "then", "than", "so", "such", "who", "whom", "which",
            "what", "when", "where", "why", "how", "all", "any", "both", "each", "few", "more",
            "most", "other", "some", "no", "nor", "only", "own", "same", "too", "very", "can",
            "will", "just", "should", "now", "also", "into", "over", "under", "about", "after",
            "before", "during", "between", "through", "against", "because", "while", "within",
            "without", "has", "have", "had", "do", "does", "did", "one", "two", "three", "new",
            "old", "less", "much", "many", "like", "well", "back", "story", "character",
            "personality", "goals", "description", "system", "prompt", "name", "age", "pronouns",
            "canonical", "identity", "keep", "consistent", "roster", "speak", "write", "use",
            "show", "avoid", "shared", "location", "world", "history", "people", "setting",
            "established", "connections", "them", "there", "here", "would", "could", "might",
            "must", "shall", "may", "however", "therefore", "though", "although", "since",
            "until", "unless", "whereas", "yet", "still", "even", "ever", "never", "always",
            "often", "sometimes", "usually", "really", "quite", "rather", "somewhat", "somehow",
            "anyone", "someone", "everyone", "nothing", "something", "anything", "everything",
            "nobody", "somebody", "anybody", "everybody", "again", "further", "once", "twice",
            "first", "second", "third", "next", "last", "finally", "thus", "hence", "yes", "okay",
            "group", "chat", "session", "scene", "roleplay", "ongoing", "ensemble", "player",
            "narration", "narrator", "message", "reply", "respond", "response", "turn",
            "paragraph", "sentence", "words", "word", "another", "present", "absent", "unknown",
            "address", "knowledge", "act", "context", "end", "start", "begin", "beginning",
            "immediate", "long", "short", "specific"
    ));

    private OpeningCastSelector() {
    }

    /** One roster member as the selector sees them. */
    public record Candidate(
            String id,
            String name,
            int order,
            String role,
            String text
    ) {
    }

    /**
     * Returns the chosen member ids in roster order.
     *
     * @param cast       active roster members
     * @param affinity   fromActor -> toActor -> points; missing pairs mean no recorded feeling
     * @param scenario   title and description of the opening scene; may be blank
     * @param size       number of members to put onstage
     */
    public static List<String> select(List<Candidate> cast, Map<String, Map<String, Integer>> affinity,
                                      String scenario, int size) {
        if (cast == null || cast.isEmpty() || size <= 0) return List.of();
        List<Candidate> ordered = cast.stream()
                .sorted(Comparator.comparingInt(Candidate::order).thenComparing(Candidate::id))
                .toList();
        if (ordered.size() <= size) return ordered.stream().map(Candidate::id).toList();

        String scenarioText = scenario == null ? "" : scenario.toLowerCase(Locale.ROOT);
        Set<String> scenarioTerms = terms(scenarioText);
        Map<String, Double> relevance = new HashMap<>();
        for (Candidate candidate : ordered) {
            relevance.put(candidate.id(), relevance(candidate, scenarioText, scenarioTerms));
        }

        // A scenario that names someone makes that person part of the opening.
        // If it names more people than the opening has room for, choose the
        // best-fitting subset of those named people.
        List<Candidate> named = namedCandidates(ordered, scenarioText, scenarioTerms);
        List<Candidate> choicePool;
        List<Candidate> required;
        if (named.size() >= size) {
            choicePool = named;
            required = List.of();
        } else {
            Set<String> namedIds = new HashSet<>();
            named.forEach(candidate -> namedIds.add(candidate.id()));
            choicePool = ordered.stream().filter(candidate -> !namedIds.contains(candidate.id())).toList();
            required = named;
        }

        Search search = new Search(choicePool, affinity, relevance, size);
        search.visit(0, new ArrayList<>(required));
        return search.best.stream()
                .sorted(Comparator.comparingInt(candidate -> candidate.order()))
                .map(Candidate::id)
                .toList();
    }

    /**
     * A full name or a unique first/last name is a direct reference. A shared
     * name token is ambiguous, so only the full name can require a member then.
     */
    private static List<Candidate> namedCandidates(List<Candidate> cast, String scenarioText,
                                                   Set<String> scenarioTerms) {
        Map<String, Integer> tokenCounts = new HashMap<>();
        for (Candidate candidate : cast) {
            for (String token : terms(candidate.name())) {
                tokenCounts.merge(token, 1, Integer::sum);
            }
        }
        Set<String> namedIds = new HashSet<>();
        for (Candidate candidate : cast) {
            if (mentions(scenarioText, candidate.name())) {
                namedIds.add(candidate.id());
                continue;
            }
            for (String token : terms(candidate.name())) {
                if (tokenCounts.getOrDefault(token, 0) == 1 && scenarioTerms.contains(token)) {
                    namedIds.add(candidate.id());
                    break;
                }
            }
        }
        return cast.stream().filter(candidate -> namedIds.contains(candidate.id())).toList();
    }

    private static boolean mentions(String scenarioText, String name) {
        if (scenarioText.isBlank() || name == null || name.isBlank()) return false;
        String quoted = Pattern.quote(name.strip().toLowerCase(Locale.ROOT));
        return Pattern.compile("(?U)\\b" + quoted + "\\b").matcher(scenarioText).find();
    }

    private static final class Search {
        private final List<Candidate> choicePool;
        private final Map<String, Map<String, Integer>> affinity;
        private final Map<String, Double> relevance;
        private final int size;
        private List<Candidate> best = List.of();
        private double bestScore = Double.NEGATIVE_INFINITY;
        private int bestOrder = Integer.MAX_VALUE;

        private Search(List<Candidate> choicePool, Map<String, Map<String, Integer>> affinity,
                       Map<String, Double> relevance, int size) {
            this.choicePool = choicePool;
            this.affinity = affinity;
            this.relevance = relevance;
            this.size = size;
        }

        private void visit(int start, List<Candidate> chosen) {
            if (chosen.size() == size) {
                evaluate(chosen);
                return;
            }
            int needed = size - chosen.size();
            for (int i = start; i <= choicePool.size() - needed; i++) {
                chosen.add(choicePool.get(i));
                visit(i + 1, chosen);
                chosen.remove(chosen.size() - 1);
            }
        }

        private void evaluate(List<Candidate> chosen) {
            double score = 0;
            int order = 0;
            Set<String> families = new HashSet<>();
            for (Candidate candidate : chosen) {
                score += relevance.getOrDefault(candidate.id(), 0.0);
                order += candidate.order();
                families.add(roleFamily(candidate.role()));
            }
            int connectedPairs = 0;
            for (int i = 0; i < chosen.size(); i++) {
                for (int j = i + 1; j < chosen.size(); j++) {
                    Candidate first = chosen.get(i);
                    Candidate second = chosen.get(j);
                    double tie = tie(first, second, affinity);
                    score += 1.5 * tie;
                    if (tie > 0) connectedPairs++;
                }
            }
            score += 1.5 * families.size();
            if (connectedPairs == chosen.size() * (chosen.size() - 1) / 2) score += 2.0;

            if (Double.compare(score, bestScore) > 0
                    || (Double.compare(score, bestScore) == 0 && order < bestOrder)) {
                bestScore = score;
                bestOrder = order;
                best = List.copyOf(chosen);
            }
        }
    }

    private static double relevance(Candidate candidate, String scenarioText, Set<String> scenarioTerms) {
        if (scenarioTerms.isEmpty()) return 0;
        Set<String> roleTerms = terms(candidate.role());
        Set<String> textTerms = terms(candidate.text());
        Set<String> nameTerms = terms(candidate.name());
        int roleOverlap = overlap(scenarioTerms, roleTerms);
        int textOverlap = overlap(scenarioTerms, textTerms);
        int nameOverlap = overlap(scenarioTerms, nameTerms);
        double nameBonus = mentions(scenarioText, candidate.name()) ? 6.0 : 0.0;
        return Math.min(6, roleOverlap) * 2.0 + Math.min(6, textOverlap)
                + Math.min(3, nameOverlap) * 2.0 + nameBonus;
    }

    private static int overlap(Set<String> left, Set<String> right) {
        int shared = 0;
        for (String term : left) {
            if (right.contains(term)) shared++;
        }
        return shared;
    }

    private static double tie(Candidate first, Candidate second,
                              Map<String, Map<String, Integer>> affinity) {
        int forward = points(affinity, first.id(), second.id());
        int reverse = points(affinity, second.id(), first.id());
        double intensity = (Math.abs(forward) + Math.abs(reverse)) / 2.0;
        double positive = Math.max(0, (forward + reverse) / 2.0);
        return intensity + 0.5 * positive;
    }

    private static int points(Map<String, Map<String, Integer>> affinity, String from, String to) {
        if (affinity == null) return 0;
        Map<String, Integer> targets = affinity.get(from);
        if (targets == null) return 0;
        return targets.getOrDefault(to, 0);
    }

    /** Coarse families used only to reward a cast that covers different work. */
    static String roleFamily(String role) {
        String value = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (containsAny(value, "assassin", "thief", "rogue", "spy", "scout", "ranger", "hunter",
                "smuggler", "shadow", "burglar", "cutpurse")) return "stealth";
        if (containsAny(value, "warrior", "fighter", "soldier", "knight", "guard", "mercenary",
                "barbarian", "duelist", "captain", "commander", "squire", "paladin", "warden",
                "blade", "swordsman", "archer")) return "combat";
        if (containsAny(value, "mage", "wizard", "sorcerer", "witch", "warlock", "cleric", "priest",
                "druid", "shaman", "alchemist", "seer", "oracle", "enchanter", "summoner")) {
            return "magic";
        }
        if (containsAny(value, "scholar", "professor", "teacher", "instructor", "scribe", "engineer",
                "inventor", "artisan", "blacksmith", "archivist", "researcher", "student",
                "apprentice", "dean", "librarian", "sage")) return "knowledge";
        if (containsAny(value, "merchant", "trader", "noble", "diplomat", "bard", "performer",
                "innkeeper", "shopkeeper", "banker", "politician", "courtier", "sailor", "dock",
                "negotiator")) return "social";
        if (containsAny(value, "healer", "medic", "farmer", "cook", "servant", "laborer", "miner",
                "porter", "guide", "navigator", "caravan", "quartermaster")) return "support";
        return "other";
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) {
            if (value.contains(word)) return true;
        }
        return false;
    }

    private static Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        if (text == null || text.isBlank()) return result;
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) result.add(word);
        }
        return result;
    }
}
