package com.eonmux.chatllm.graphql;

import graphql.schema.DataFetchingEnvironment;
import io.smallrye.graphql.api.Context;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.inject.Instance;

import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves omitted fields versus explicit nulls in GraphQL input objects. */
public final class GraphQLInputs {
    private GraphQLInputs() {}

    public static Map<String, Object> suppliedInput(Object input, Instance<Context> context) {
        if (context != null && context.isResolvable()) {
            DataFetchingEnvironment environment = context.get().unwrap(DataFetchingEnvironment.class);
            if (environment != null && environment.containsArgument("input")) {
                Map<String, Object> supplied = environment.getArgument("input");
                return supplied == null ? Map.of() : new LinkedHashMap<>(supplied);
            }
        }
        // Direct Java callers have no argument-presence metadata. Null bean fields
        // cannot be distinguished from omitted values, so leave them unchanged.
        if (input == null) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>(JsonObject.mapFrom(input).getMap());
        values.values().removeIf(value -> value == null);
        return values;
    }
}
