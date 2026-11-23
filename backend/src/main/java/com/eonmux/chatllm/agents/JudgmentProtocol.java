package com.eonmux.chatllm.agents;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonObject;
import java.util.Set;

/** Strict wire format for decisions: exactly one complete object, no guessed or repaired verdicts. */
public final class JudgmentProtocol {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private JudgmentProtocol() {}

    public static JsonObject object(String text) {
        try {
            var tree = JSON.readTree(text);
            if (tree == null || !tree.isObject()) throw new IllegalArgumentException("Expected one judgment object");
            return new JsonObject(tree.toString());
        } catch (Exception error) {
            throw new IllegalArgumentException("Expected one complete judgment object", error);
        }
    }
    static boolean keys(JsonObject value, String... allowed) {
        return Set.of(allowed).containsAll(value.fieldNames());
    }
    static boolean text(JsonObject value, String key) {
        return value.getValue(key) instanceof String text && !text.isBlank();
    }
    static boolean number(JsonObject value, String key) {
        return value.getValue(key) instanceof Number n && Double.isFinite(n.doubleValue());
    }
    static boolean review(JsonObject value) {
        if (!keys(value,"approved","reason","severity","category","suggestion")
                || !(value.getValue("approved") instanceof Boolean)) return false;
        for (String key : new String[]{"reason","severity","category","suggestion"})
            if (value.containsKey(key) && !(value.getValue(key) instanceof String)) return false;
        if (!value.getBoolean("approved") && !text(value, "reason")) return false;
        return !value.containsKey("severity") || Set.of("minor","moderate","critical").contains(value.getString("severity"));
    }
}
