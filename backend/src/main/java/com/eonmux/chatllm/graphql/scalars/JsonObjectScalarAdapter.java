package com.eonmux.chatllm.graphql.scalars;

import io.vertx.core.json.JsonObject;

import io.smallrye.graphql.api.Scalar;
import io.smallrye.graphql.api.AdaptToScalar;

/**
 * Marker interface so SmallRye GraphQL knows to treat JsonObject as a JSON scalar.
 * The actual mapping is done by registering the adapter below.
 */
public class JsonObjectScalarAdapter {
    // This class is not instantiated. The adapter is registered via SPI / annotations on the model fields.
}
