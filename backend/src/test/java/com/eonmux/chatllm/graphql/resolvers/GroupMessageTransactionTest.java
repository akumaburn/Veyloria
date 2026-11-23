package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.inputs.SendGroupMessageInput;
import com.eonmux.chatllm.services.GroupChatService;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.*;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.eonmux.chatllm.testing.SqlTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupMessageTransactionTest {
    private Vertx vertx;
    private GroupChatResolver resolver;
    private String owner;
    private String messageId;
    private boolean failCountUpdate;
    private String conversationState = "WAITING_FOR_USER";
    private final java.util.concurrent.atomic.AtomicInteger consumedUserRequests = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicBoolean committed = new AtomicBoolean();
    private final CompletableFuture<Boolean> responseTriggeredAfterCommit = new CompletableFuture<>();

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        vertx = Vertx.vertx();
        owner = UUID.randomUUID().toString();
        resolver = new GroupChatResolver();
        resolver.vertx = vertx;
        resolver.graphQLAuthContext = mock(GraphQLAuthContext.class);
        resolver.groupChatService = mock(GroupChatService.class);
        when(resolver.groupChatService.revealAddressedCastMember(anyString()))
                .thenReturn(Uni.createFrom().nullItem());
        AuthContext auth = mock(AuthContext.class);
        when(auth.getUserId()).thenReturn(UUID.fromString(owner));
        when(resolver.graphQLAuthContext.requireAuth()).thenReturn(auth);
        Pool transactionQueries = pool((sql, params) -> {
            if (sql.contains("SELECT c.groupState FROM conversation")) {
                return rows(row(Map.of("groupState", conversationState)));
            }
            if (sql.contains("INSERT INTO message")) {
                messageId = params.getString(0);
                return affected(1);
            }
            if (sql.contains("UPDATE interjection_request")) {
                consumedUserRequests.incrementAndGet();
                return affected(1);
            }
            if (sql.contains("UPDATE conversation")) {
                if (failCountUpdate) throw new IllegalStateException("Counter update failed");
                assertFalse(sql.contains("consecutiveResponseCount"),
                        "A typed message must not reset the turn budget; the claim does that");
                assertFalse(sql.contains("lastSpeakerId = NULL"), "Human messages must preserve round-robin position");
                return affected(1);
            }
            if (sql.contains("SELECT * FROM message")) return rows(row(Map.of(
                    "messageUUID", messageId, "conversationId", paramsForChat(), "ownerId", owner,
                    "content", "hello", "role", "user", "createdAt", LocalDateTime.now())));
            throw new AssertionError(sql);
        });
        SqlConnection connection = mock(SqlConnection.class);
        when(connection.preparedQuery(anyString())).thenAnswer(call -> transactionQueries.preparedQuery(call.getArgument(0)));
        resolver.mysqlPool = pool((sql, params) -> {
            responseTriggeredAfterCommit.complete(committed.get());
            return affected(0); // No next turn needed for this transaction test.
        });
        when(resolver.mysqlPool.withTransaction(any())).thenAnswer(call -> {
            Function<SqlConnection, Uni<Object>> transaction = call.getArgument(0);
            return Uni.createFrom().deferred(() -> transaction.apply(connection)).invoke(result -> committed.set(true));
        });
    }

    @AfterEach void close() { vertx.closeAndAwait(); }

    @Test void sendsTriggerGenerationOnlyAfterTheTransactionCommits() throws Exception {
        var result = resolver.sendGroupMessage(new SendGroupMessageInput(paramsForChat(), "hello")).await().indefinitely();
        assertEquals(messageId, result.getId().toString());
        assertTrue(responseTriggeredAfterCommit.get(2, TimeUnit.SECONDS));
        verify(resolver.mysqlPool).withTransaction(any());
    }

    @Test void aManualContinueClaimsTheTurnThroughTheServiceAndRetriesANamedMember() {
        com.eonmux.chatllm.models.Character next = com.eonmux.chatllm.models.Character.builder()
                .id(UUID.randomUUID()).name("Next").build();
        when(resolver.groupChatService.revealAddressedCastMember(anyString()))
                .thenReturn(Uni.createFrom().item("Next"));
        when(resolver.groupChatService.startCharacterTurn(anyString(), anyString()))
                .thenReturn(Uni.createFrom().item(next));
        resolver.mysqlPool = pool((sql, params) -> {
            if (sql.contains("COUNT(*)")) return rows(row(Map.of("count", 1L)));
            throw new AssertionError("Unexpected query: " + sql);
        });

        var message = resolver.triggerNextCharacterResponse(paramsForChat()).await().indefinitely();

        assertEquals("Next", message.getDisplayName());
        verify(resolver.groupChatService).revealAddressedCastMember(paramsForChat());
        verify(resolver.groupChatService).startCharacterTurn(paramsForChat(), owner);
    }

    @Test void aContinueThatLosesTheClaimReportsWithoutSpeaking() {
        when(resolver.groupChatService.revealAddressedCastMember(anyString()))
                .thenReturn(Uni.createFrom().item("Next"));
        when(resolver.groupChatService.startCharacterTurn(anyString(), anyString()))
                .thenReturn(Uni.createFrom().nullItem());
        resolver.mysqlPool = pool((sql, params) -> {
            if (sql.contains("COUNT(*)")) return rows(row(Map.of("count", 1L)));
            throw new AssertionError("Unexpected query: " + sql);
        });

        assertThrows(RuntimeException.class,
                () -> resolver.triggerNextCharacterResponse(paramsForChat()).await().indefinitely());
    }

    @Test void aPausedConversationRefusesTheMessageInsteadOfSwallowingIt() {
        conversationState = "PAUSED";

        assertThrows(RuntimeException.class, () -> resolver.sendGroupMessage(
                new SendGroupMessageInput(paramsForChat(), "hello")).await().indefinitely());

        assertFalse(committed.get(), "A rejected message must not commit");
        assertEquals(0, consumedUserRequests.get());
    }

    @Test void aTypedMessageRetiresTheUsersPendingFloorRequest() {
        resolver.sendGroupMessage(new SendGroupMessageInput(paramsForChat(), "hello")).await().indefinitely();

        assertEquals(1, consumedUserRequests.get());
    }

    @Test void failedCountUpdatesDoNotDispatchAResponse() throws Exception {
        failCountUpdate = true;
        assertThrows(IllegalStateException.class, () -> resolver.sendGroupMessage(
                new SendGroupMessageInput(paramsForChat(), "hello")).await().indefinitely());
        assertFalse(committed.get());
        vertx.setTimer(50, ignored -> responseTriggeredAfterCommit.complete(false));
        assertFalse(responseTriggeredAfterCommit.get(2, TimeUnit.SECONDS));
        verify(resolver.mysqlPool, never()).preparedQuery(anyString());
    }

    private String paramsForChat() { return "00000000-0000-0000-0000-000000000001"; }
}
