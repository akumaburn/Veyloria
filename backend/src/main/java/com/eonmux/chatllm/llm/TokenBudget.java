package com.eonmux.chatllm.llm;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps an OpenAI-compatible request inside the model's context window.
 *
 * <p>The provider counts prompt tokens plus {@code max_tokens}. A request that
 * asks for the full configured output length on top of a non-empty prompt is
 * rejected even though each value is valid alone. This class trims the oldest
 * history and reduces the completion budget so the sum fits.</p>
 */
@Slf4j
public final class TokenBudget {
    /** Room for tokenizer differences between this estimate and the provider. */
    static final int SAFETY_MARGIN = 4096;
    /** A reply shorter than this is not useful; fail clearly instead. */
    static final int MIN_COMPLETION = 256;

    private TokenBudget() {
    }

    /** Conservative estimate: three characters per token, plus message framing. */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int codePoints = text.codePointCount(0, text.length());
        return (int) Math.ceil(codePoints / 3.0) + 4;
    }

    public static int estimateMessages(JsonArray messages) {
        if (messages == null) return 0;
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            Object value = messages.getValue(i);
            if (value instanceof JsonObject message) {
                total += estimate(message.getString("content"));
            }
        }
        return total;
    }

    /**
     * Removes the oldest non-system turns until the prompt fits. The system
     * message and the newest message are always kept.
     */
    public static JsonArray fitMessages(JsonArray messages, int contextSize) {
        if (messages == null || contextSize <= 0) return messages;
        int budget = contextSize - MIN_COMPLETION - SAFETY_MARGIN;
        if (budget <= 0) return messages;

        JsonArray fitted = messages.copy();
        int total = estimateMessages(fitted);
        boolean systemFirst = fitted.size() > 0
                && fitted.getValue(0) instanceof JsonObject first
                && "system".equals(first.getString("role"));
        int oldest = systemFirst ? 1 : 0;

        while (total > budget && fitted.size() > oldest + 1) {
            JsonObject removed = fitted.getJsonObject(oldest);
            total -= estimate(removed.getString("content"));
            fitted.remove(oldest);
            log.warn("Trimmed the oldest conversation turn to fit the context window");
        }
        return fitted;
    }

    /**
     * Applies the fitted history and completion ceiling to a request. Throws a
     * readable error when the newest message alone cannot fit.
     */
    public static JsonObject fitRequest(JsonObject params, JsonArray messages, int contextSize) {
        if (params == null || contextSize <= 0) return params;
        JsonArray fitted = fitMessages(messages, contextSize);
        if (fitted != messages) params.put("messages", fitted);

        int inputTokens = estimateMessages(fitted);
        int available = contextSize - inputTokens - SAFETY_MARGIN;
        if (available < MIN_COMPLETION) {
            throw new IllegalArgumentException(
                    "This conversation is too long for the model's context window. Start a new chat or shorten the latest message.");
        }

        int requested = params.getInteger("max_tokens", -1);
        if (requested > available) {
            log.debug("Reduced max_tokens from {} to {} to fit the context window (estimated prompt {} tokens)",
                    requested, available, inputTokens);
            params.put("max_tokens", available);
        }
        return params;
    }
}
