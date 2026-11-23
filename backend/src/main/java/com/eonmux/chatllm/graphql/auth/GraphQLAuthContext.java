package com.eonmux.chatllm.graphql.auth;

import com.eonmux.chatllm.services.auth.AuthContext;
import com.eonmux.chatllm.services.auth.JWTAuthProvider;
import graphql.schema.DataFetchingEnvironment;
import io.smallrye.graphql.api.Context;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * CDI RequestScoped bean that provides auth context to GraphQL resolver methods.
 *
 * Extracts and validates the JWT Bearer token from one of:
 * 1. The HTTP {@code Authorization} header (queries / mutations).
 * 2. The graphql-transport-ws {@code connection_init.payload.authorization}
 *    field, surfaced by SmallRye via {@code init-payload} in the GraphQL
 *    execution context. Browsers cannot set custom headers on a WebSocket
 *    handshake, so subscriptions rely exclusively on this path.
 */
@RequestScoped
@Slf4j
public class GraphQLAuthContext {

    @Inject
    JWTAuthProvider jwtAuthProvider;

    @Inject
    Instance<HttpServerRequest> requestInstance;

    @Inject
    Instance<Context> graphqlContextInstance;

    private AuthContext authContext;
    private boolean resolved;

    /**
     * Lazily resolves and returns the AuthContext from the current request.
     * Returns null if no valid token is present.
     */
    public AuthContext getAuthContext() {
        if (!resolved) {
            resolved = true;
            authContext = resolveFromRequest();
        }
        return authContext;
    }

    /**
     * Returns the AuthContext, throwing if the user is not authenticated.
     *
     * @throws AuthException if not authenticated or token is expired
     */
    public AuthContext requireAuth() {
        AuthContext ctx = getAuthContext();
        if (ctx == null || !ctx.isAuthenticated()) {
            throw new AuthException("Not authenticated");
        }
        if (ctx.isExpired()) {
            throw new AuthException("Token expired");
        }
        return ctx;
    }

    /**
     * Returns the AuthContext, throwing if the user does not have admin role.
     *
     * @throws AuthException if not authenticated or not admin
     */
    public AuthContext requireAdmin() {
        AuthContext ctx = requireAuth();
        if (!ctx.isAdmin()) {
            throw new AuthException("Unauthorized");
        }
        return ctx;
    }

    /**
     * Get the client IP address from the current request.
     */
    public String getClientIp() {
        HttpServerRequest request = currentRequest();
        if (request == null || request.remoteAddress() == null) {
            return "unknown";
        }
        return request.remoteAddress().host();
    }

    /**
     * Resolve the JWT from the current request — HTTP header first, then
     * the graphql-transport-ws connection_init payload — and validate it.
     */
    private AuthContext resolveFromRequest() {
        HttpServerRequest request = currentRequest();

        String token = extractTokenFromHttpHeader(request);
        if (token == null) {
            token = extractTokenFromInitPayload();
        }

        if (token == null) {
            log.debug("No Bearer token found in Authorization header or connection_init payload");
            return null;
        }

        try {
            AuthContext ctx = jwtAuthProvider.validateToken(token);
            if (ctx != null && request != null) {
                ctx.setIpAddress(request.remoteAddress() != null ? request.remoteAddress().host() : null);
                ctx.setUserAgent(request.getHeader("User-Agent"));
            }
            return ctx;
        } catch (Exception e) {
            log.warn("Failed to validate JWT token: {}", e.getMessage());
            return null;
        }
    }

    private HttpServerRequest currentRequest() {
        try {
            return requestInstance.isResolvable() ? requestInstance.get() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractTokenFromHttpHeader(HttpServerRequest request) {
        if (request == null) return null;
        String authHeader;
        try {
            authHeader = request.getHeader("Authorization");
        } catch (Exception e) {
            return null;
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    @SuppressWarnings("unchecked")
    private String extractTokenFromInitPayload() {
        Context graphqlContext;
        try {
            graphqlContext = graphqlContextInstance.isResolvable() ? graphqlContextInstance.get() : null;
        } catch (Exception e) {
            log.debug("init-payload: graphql context not resolvable: {}", e.getMessage());
            return null;
        }
        if (graphqlContext == null) {
            log.debug("init-payload: graphql context is null");
            return null;
        }

        DataFetchingEnvironment dfe;
        try {
            dfe = graphqlContext.unwrap(DataFetchingEnvironment.class);
        } catch (Exception e) {
            log.debug("init-payload: failed to unwrap DataFetchingEnvironment: {}", e.getMessage());
            return null;
        }
        if (dfe == null || dfe.getGraphQlContext() == null) {
            log.debug("init-payload: dfe or graphQlContext is null");
            return null;
        }

        Object payload = dfe.getGraphQlContext().get("init-payload");
        if (payload == null) {
            // No init-payload at all on this subscription. This is the dominant
            // failure mode when the client opened the WebSocket BEFORE storing
            // the auth token, so the connection_init.payload was empty.
            log.warn("init-payload: missing entirely from GraphQL context — "
                    + "client likely opened WebSocket before login completed");
            return null;
        }
        if (!(payload instanceof Map)) {
            log.warn("init-payload: present but not a Map (type: {})",
                    payload.getClass().getSimpleName());
            return null;
        }

        Map<String, Object> initPayload = (Map<String, Object>) payload;
        Object auth = initPayload.get("authorization");
        if (auth == null) {
            // Some clients use "Authorization" (capitalised); accept either.
            auth = initPayload.get("Authorization");
        }
        if (auth == null) {
            log.warn("init-payload: present (keys={}) but no authorization/Authorization field — "
                    + "client sent connection_init without auth header",
                    initPayload.keySet());
            return null;
        }
        // SmallRye GraphQL parses connection_init.payload via JSON-P (Parsson),
        // so string values arrive as jakarta.json.JsonString rather than
        // java.lang.String. Accept either form. (Some older SmallRye versions
        // delivered plain Strings — keep that path for compatibility.)
        String authStr;
        if (auth instanceof String) {
            authStr = (String) auth;
        } else if (auth instanceof JsonString) {
            authStr = ((JsonString) auth).getString();
        } else {
            log.warn("init-payload.authorization: unsupported value type (got {})",
                    auth.getClass().getName());
            return null;
        }
        authStr = authStr.trim();
        if (authStr.isEmpty()) {
            log.warn("init-payload.authorization: empty string");
            return null;
        }
        if (authStr.startsWith("Bearer ")) {
            String token = authStr.substring(7).trim();
            if (token.isEmpty()) {
                log.warn("init-payload.authorization: 'Bearer ' prefix with empty token");
                return null;
            }
            return token;
        }
        return authStr;
    }
}
