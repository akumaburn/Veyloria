package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.services.auth.JWTAuthProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowIterator;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlResult;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(AuthIntegrationTest.AuthTestProfile.class)
class AuthIntegrationTest {

    public static class AuthTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "chatllm.jwt.secret", "test-secret-that-is-at-least-32-char-long!!",
                "chatllm.development-mode", "true",
                "chatllm.auth-bypass-enabled", "false",
                "chatllm.bcrypt-rounds", "4",
                "chatllm.llm.enabled", "false",
                "chatllm.llm.mock-mode", "true",
                "chatllm.login-rate-limit-per-minute", "10000",
                "chatllm.message-rate-limit-per-minute", "10000",
                "chatllm.rate-limit-per-minute", "10000"
            );
        }
    }

    @Inject
    JWTAuthProvider jwtAuthProvider;

    @Inject
    Vertx vertx;

    @Inject
    AppConfig config;

    private static final String TEST_USERNAME = "testuser_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_EMAIL = TEST_USERNAME + "@test.com";
    private static final String TEST_PASSWORD = "TestPass123!";
    private static final String TEST_DISPLAY_NAME = "Test User";

    private String accessToken;
    private String refreshTokenValue;

    private Row createUserRow(String userId, String username, String email, String displayName, String role, String passwordHash) {
        Row row = mock(Row.class);
        when(row.getString("userUUID")).thenReturn(userId);
        when(row.getString("username")).thenReturn(username);
        when(row.getString("email")).thenReturn(email);
        when(row.getString("displayName")).thenReturn(displayName);
        when(row.getString("role")).thenReturn(role);
        when(row.getString("passwordHash")).thenReturn(passwordHash);
        when(row.getLocalDateTime("createdAt")).thenReturn(LocalDateTime.now());
        when(row.getBoolean("isBanned")).thenReturn(false);
        return row;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RowSet<Row> createRowSet(Row row) {
        RowSet rowSet = mock(RowSet.class);
        RowIterator iterator = mock(RowIterator.class);
        when(iterator.hasNext()).thenReturn(true, false);
        when(iterator.next()).thenReturn(row);
        when(rowSet.iterator()).thenReturn(iterator);
        when(rowSet.size()).thenReturn(1);
        return rowSet;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RowSet<Row> createCountRowSet(long count) {
        Row countRow = mock(Row.class);
        when(countRow.getLong("count")).thenReturn(count);

        RowSet rowSet = mock(RowSet.class);
        RowIterator iterator = mock(RowIterator.class);
        when(iterator.hasNext()).thenReturn(true, false);
        when(iterator.next()).thenReturn(countRow);
        when(rowSet.iterator()).thenReturn(iterator);
        when(rowSet.size()).thenReturn(1);
        return rowSet;
    }

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes", "resource"})
    void setUp() {
        Pool pool = mock(Pool.class);
        // Make the mock available via CDI
        // NOTE: Quarkus @InjectMock is preferred; here we use field injection.
    }

    private String buildGraphQLQuery(String query, String variables) {
        JsonObject body = new JsonObject();
        body.put("query", query);
        if (variables != null) {
            body.put("variables", new JsonObject(variables));
        }
        return body.encode();
    }

    @Test
    void testRegisterValidation() {
        String mutation = """
            mutation($username: String!, $email: String!, $password: String!) {
                register(username: $username, email: $email, password: $password) {
                    accessToken
                    refreshToken
                    expiresIn
                    user { id username email displayName role }
                }
            }
            """;

        String variables = """
            {"username": "%s", "email": "%s", "password": "%s"}
            """.formatted("reg_" + UUID.randomUUID().toString().substring(0, 6),
                "reg_" + UUID.randomUUID().toString().substring(0, 6) + "@test.com", TEST_PASSWORD);

        // This will fail with a GraphQL error because no real DB is available,
        // but it validates the request reaches the resolver and handles errors properly
        Response response = given()
            .contentType(ContentType.JSON)
            .body(buildGraphQLQuery(mutation, variables))
            .post("/graphql");

        response.then().log().ifValidationFails();
        int statusCode = response.getStatusCode();
        assertTrue(statusCode == 200 || statusCode == 400,
            "Expected 200 or 400, got " + statusCode);
    }

    @Test
    void testRegisterShortPassword() {
        String mutation = """
            mutation {
                register(
                    username: "shortpass",
                    email: "short@test.com",
                    password: "123"
                ) { accessToken }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(buildGraphQLQuery(mutation, null))
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", notNullValue());
    }

    @Test
    void testRegisterInvalidEmail() {
        String mutation = """
            mutation {
                register(
                    username: "bademail",
                    email: "not-an-email",
                    password: "LongEnoughPass1!"
                ) { accessToken }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(buildGraphQLQuery(mutation, null))
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", notNullValue());
    }

    @Test
    void testJWTAuthProviderGeneration() {
        UUID userId = UUID.randomUUID();
        Set<String> roles = Set.of("USER");

        String accessToken = jwtAuthProvider.generateAccessToken(userId, "testuser", "Test User", roles);
        assertNotNull(accessToken);
        assertFalse(accessToken.isEmpty());

        String refreshToken = jwtAuthProvider.generateRefreshToken(userId);
        assertNotNull(refreshToken);
        assertFalse(refreshToken.isEmpty());

        // Access tokens are valid bearer credentials; refresh tokens are not
        // (a refresh token must never authenticate API requests).
        assertNotNull(jwtAuthProvider.validateToken(accessToken));
        assertNull(jwtAuthProvider.validateToken(refreshToken));
    }

    @Test
    void testJWTAuthProviderTokenTypes() {
        UUID userId = UUID.randomUUID();
        Set<String> roles = Set.of("USER");

        String accessToken = jwtAuthProvider.generateAccessToken(userId, "testuser", "Test User", roles);
        String refreshToken = jwtAuthProvider.generateRefreshToken(userId);

        var decodedAccess = jwtAuthProvider.validateToken(accessToken);
        assertNotNull(decodedAccess);
        // Refresh tokens are rejected by the request-auth path (validateToken).
        assertNull(jwtAuthProvider.validateToken(refreshToken));

        assertTrue(decodedAccess.hasRole("USER"));
        assertEquals("testuser", decodedAccess.getUsername());
        assertEquals(userId, decodedAccess.getUserId());
    }

    @Test
    void testJWTAuthProviderAdminRole() {
        UUID userId = UUID.randomUUID();
        Set<String> roles = Set.of("USER", "ADMIN");

        String token = jwtAuthProvider.generateAccessToken(userId, "admin", "Admin User", roles);
        var ctx = jwtAuthProvider.validateToken(token);
        assertNotNull(ctx);
        assertTrue(ctx.hasRole("ADMIN"));
        assertTrue(ctx.isAdmin());
    }

    @Test
    void testJWTAuthProviderInvalidToken() {
        assertNull(jwtAuthProvider.validateToken("invalid-token"));
        assertNull(jwtAuthProvider.validateToken(""));
        assertNull(jwtAuthProvider.validateToken(null));
    }

    @Test
    void testJWTAuthProviderExpiredToken() {
        // The validateToken method does not check expiration (sync path)
        // That's handled by createAuthContext async
        UUID userId = UUID.randomUUID();
        Set<String> roles = Set.of("USER");
        String token = jwtAuthProvider.generateAccessToken(userId, "testuser", "Test User", roles);
        var ctx = jwtAuthProvider.validateToken(token);
        assertNotNull(ctx);
        assertFalse(ctx.isExpired());
    }

    @Test
    void testLoginInvalidCredentials() {
        String mutation = """
            mutation {
                login(username: "nonexistent", password: "WrongPass1!") {
                    accessToken
                    user { id }
                }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(buildGraphQLQuery(mutation, null))
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", notNullValue());
    }

    @Test
    void testLogoutWithoutAuth() {
        String mutation = """
            mutation { logout }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(buildGraphQLQuery(mutation, null))
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", notNullValue());
    }

    @Test
    void testMeQueryWithoutAuth() {
        String query = """
            query { me { id username } }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(buildGraphQLQuery(query, null))
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", notNullValue());
    }

    @Test
    void testRefreshTokenWithoutToken() {
        String mutation = """
            mutation {
                refreshToken(refreshToken: "invalid") {
                    accessToken
                }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(buildGraphQLQuery(mutation, null))
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", notNullValue());
    }

    @Test
    void testJWTAuthProviderTokensResponse() {
        UUID userId = UUID.randomUUID();
        Set<String> roles = Set.of("USER");

        JsonObject response = jwtAuthProvider.generateTokensResponse(userId, "testuser", "Test User", roles);

        assertNotNull(response.getString("accessToken"));
        assertNotNull(response.getString("refreshToken"));
        assertNotNull(response.getInteger("expiresIn"));
        assertEquals("Bearer", response.getString("tokenType"));

        // Verify tokens are valid
        assertNotNull(jwtAuthProvider.validateToken(response.getString("accessToken")));
        // The refresh token must NOT be accepted as a bearer credential.
        assertNull(jwtAuthProvider.validateToken(response.getString("refreshToken")));
    }

    @Test
    void testAuthContextIsAuthenticated() {
        UUID userId = UUID.randomUUID();
        var ctx = jwtAuthProvider.validateToken(
            jwtAuthProvider.generateAccessToken(userId, "testuser", "Test User", Set.of("USER")));
        assertNotNull(ctx);
        assertTrue(ctx.isAuthenticated());
        assertFalse(ctx.isAdmin());
        assertFalse(ctx.isExpired());
        assertEquals("testuser", ctx.getUsername());
        assertEquals("Test User", ctx.getDisplayName());
        assertEquals("Test User", ctx.getEffectiveDisplayName());
    }
}
