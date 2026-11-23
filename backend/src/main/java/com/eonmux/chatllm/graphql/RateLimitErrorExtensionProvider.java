package com.eonmux.chatllm.graphql;

import com.eonmux.chatllm.graphql.resolvers.MutationResolver.RateLimitExceededException;
import io.smallrye.graphql.api.ErrorExtensionProvider;
import jakarta.json.Json;
import jakarta.json.JsonValue;

import java.util.concurrent.CompletionException;

public class RateLimitErrorExtensionProvider implements ErrorExtensionProvider {
    @Override
    public String getKey() {
        return "rateLimit";
    }

    @Override
    public JsonValue mapValueFrom(Throwable exception) {
        if (exception instanceof CompletionException && exception.getCause() != null) {
            exception = exception.getCause();
        }
        if (exception instanceof RateLimitExceededException rle) {
            return Json.createObjectBuilder()
                .add("retryAfterSeconds", rle.getRetryAfterSeconds())
                .add("limit", rle.getLimit())
                .add("remaining", rle.getRemaining())
                .build();
        }
        return null;
    }
}
