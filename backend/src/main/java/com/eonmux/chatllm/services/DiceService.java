package com.eonmux.chatllm.services;

import com.eonmux.chatllm.utils.ServiceHelper;
import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.DiceRoll;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
@Slf4j
public class DiceService {
    private static final Pattern DICE_PATTERN = Pattern.compile("^(\\d+)?d(\\d+|F|%)([+-]\\d+)?$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_DICE_COUNT = 100;
    private static final int MAX_DICE_SIDES = 1_000_000;
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    private final SecureRandom random = new SecureRandom();

    public boolean isValidExpression(String expression) {
        return expression != null && DICE_PATTERN.matcher(expression.trim()).matches();
    }

    public DiceRoll roll(String expression) {
        Matcher matcher = DICE_PATTERN.matcher(expression.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid dice expression: " + expression);
        }

        int count = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : 1;
        String dieStr = matcher.group(2);
        int modifier = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;

        // d% is a percentile die (1-100). Parse it before the numeric die
        // branch, which would otherwise throw NumberFormatException on '%'.
        boolean isPercentile = dieStr.equalsIgnoreCase("%");
        boolean isFudge = dieStr.equalsIgnoreCase("F");
        int sides = isFudge ? 3 : (isPercentile ? 100 : Integer.parseInt(dieStr));

        // Cap roll size to prevent CPU exhaustion via expressions like 100000000d100000000.
        if (count > MAX_DICE_COUNT || sides > MAX_DICE_SIDES) {
            throw new IllegalArgumentException("Dice expression exceeds limits (max " + MAX_DICE_COUNT + " dice, " + MAX_DICE_SIDES + " sides)");
        }

        // random.nextInt(0) throws; a die must have at least one side.
        if (count < 1 || sides < 1) {
            throw new IllegalArgumentException("Dice expression must have at least 1 die and 1 side");
        }

        List<Integer> results = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < count; i++) {
            int result;
            if (isFudge) {
                result = random.nextInt(3) - 1; // -1, 0, 1
            } else {
                result = random.nextInt(sides) + 1;
            }
            results.add(result);
            total += result;
        }
        total += modifier;

        return DiceRoll.builder()
            .rollId(UUID.randomUUID())
            .expression(expression.trim())
            .results(results)
            .total(total)
            .modifier(modifier)
            .build();
    }

    public DiceRoll rollWithAdvantage(String expression) {
        DiceRoll roll1 = roll(expression);
        DiceRoll roll2 = roll(expression);
        return roll1.getTotal() >= roll2.getTotal() ? roll1 : roll2;
    }

    public DiceRoll rollWithDisadvantage(String expression) {
        DiceRoll roll1 = roll(expression);
        DiceRoll roll2 = roll(expression);
        return roll1.getTotal() <= roll2.getTotal() ? roll1 : roll2;
    }

    public Uni<DiceRoll> rollAndSave(String conversationId, String characterId, String userId,
                                         String expression, String purpose, Boolean isHidden) {
        DiceRoll result = roll(expression);
        result.setConversationId(UUID.fromString(conversationId));
        if (characterId != null) result.setCharacterId(UUID.fromString(characterId));
        if (userId != null) result.setUserId(UUID.fromString(userId));
        result.setPurpose(purpose);
        result.setIsHidden(isHidden != null ? isHidden : false);

        String insert = """
            INSERT INTO dice_roll (rollId, conversationId, characterId, userId, expression, results, total, modifier, purpose, isHidden)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        return pool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(result.getRollId().toString()).addValue(conversationId).addValue(characterId)
                .addValue(userId).addValue(result.getExpression()).addValue(new JsonArray(result.getResults()).encode())
                .addValue(result.getTotal()).addValue(result.getModifier()).addValue(purpose)
                .addValue(result.getIsHidden()))
            .map(v -> result);
    }

    private static final int MAX_HISTORY_LIMIT = 200;

    public Uni<List<DiceRoll>> getHistory(String conversationId, Integer limit) {
        // Cap the caller-supplied limit so a single request can never
        // materialize an unbounded result set (contrast with the input caps).
        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), MAX_HISTORY_LIMIT) : 50;
        String query = "SELECT * FROM dice_roll WHERE conversationId = ? ORDER BY createdAt DESC LIMIT ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId, effectiveLimit))
            .map(rows -> {
                List<DiceRoll> rolls = new ArrayList<>();
                for (Row row : rows) rolls.add(mapRowToDiceRoll(row));
                return rolls;
            });
    }

    private DiceRoll mapRowToDiceRoll(Row row) {
        DiceRoll d = new DiceRoll();
        d.setRollId(UUID.fromString(row.getString("rollId")));
        d.setConversationId(UUID.fromString(row.getString("conversationId")));
        if (row.getString("characterId") != null) d.setCharacterId(UUID.fromString(row.getString("characterId")));
        if (row.getString("userId") != null) d.setUserId(UUID.fromString(row.getString("userId")));
        d.setExpression(row.getString("expression"));
        try {
            JsonArray arr = new JsonArray(ServiceHelper.safeGetJsonText(row, "results"));
            List<Integer> intList = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) intList.add(arr.getInteger(i));
            d.setResults(intList);
        } catch (Exception e) { d.setResults(new ArrayList<>()); }
        d.setTotal(row.getInteger("total"));
        d.setModifier(row.getInteger("modifier"));
        d.setPurpose(row.getString("purpose"));
        d.setIsHidden(row.getBoolean("isHidden"));
        if (row.getString("messageId") != null) d.setMessageId(UUID.fromString(row.getString("messageId")));
        d.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        return d;
    }
}
