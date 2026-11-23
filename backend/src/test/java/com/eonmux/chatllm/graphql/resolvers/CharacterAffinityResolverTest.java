package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.services.CharacterAffinityService;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CharacterAffinityResolverTest {
    CharacterAffinityResolver resolver;
    UUID owner = UUID.randomUUID();
    String character = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();
    @BeforeEach void setup() {
        resolver = new CharacterAffinityResolver();
        resolver.authContext = mock(GraphQLAuthContext.class);
        AuthContext auth = mock(AuthContext.class);
        when(auth.getUserId()).thenReturn(owner);
        when(resolver.authContext.requireAuth()).thenReturn(auth);
        resolver.service = mock(CharacterAffinityService.class);
    }
    @Test void usesAuthenticatedOwnerAndNormalizesTheReactionAndReason() throws Exception {
        when(resolver.service.react(owner.toString(), character, null, "LOVE", "Helped me", key))
                .thenReturn(Future.succeededFuture(new com.eonmux.chatllm.models.CharacterAffinity()));
        resolver.reactToCharacter(character, null, "love", " Helped me ", key).get();
        verify(resolver.service).react(owner.toString(), character, null, "LOVE", "Helped me", key);
    }
    @Test void setsAnExactPointValueForTierControls() throws Exception {
        when(resolver.service.set(owner.toString(), character, null, 60, "A promise kept", key))
                .thenReturn(Future.succeededFuture(new com.eonmux.chatllm.models.CharacterAffinity()));
        resolver.setCharacterAffinity(character, null, 60, " A promise kept ", key).get();
        verify(resolver.service).set(owner.toString(), character, null, 60, "A promise kept", key);
    }
    @Test void rejectsOutOfRangePointValues() {
        assertThrows(Exception.class, () -> resolver.setCharacterAffinity(character, null, 101, null, key));
        assertThrows(Exception.class, () -> resolver.setCharacterAffinity(character, null, -101, null, key));
        assertThrows(Exception.class, () -> resolver.setCharacterAffinity(character, character, 0, null, key));
        verifyNoInteractions(resolver.service);
    }
    @Test void invalidRequestsNeverTouchTheLedger() {
        assertThrows(Exception.class, () -> resolver.reactToCharacter(character, character, "LIKE", null, key));
        assertThrows(Exception.class, () -> resolver.reactToCharacter(null, null, "LIKE", null, key));
        assertThrows(Exception.class, () -> resolver.reactToCharacter(character, null, "UNKNOWN", null, key));
        assertThrows(Exception.class, () -> resolver.reactToCharacter(character, null, "LIKE", "a".repeat(501), key));
        assertThrows(Exception.class, () -> resolver.reactToCharacter(character, null, "LIKE", null, "invalid"));
        verifyNoInteractions(resolver.service);
    }
    @Test void rejectsUnauthenticatedReadsAndWrites() {
        when(resolver.authContext.requireAuth()).thenThrow(new SecurityException("Authentication required"));
        assertThrows(SecurityException.class, () -> resolver.characterAffinities());
        assertThrows(SecurityException.class, () -> resolver.relationshipActors());
        assertThrows(SecurityException.class, () -> resolver.reactToCharacter(character, null, "LIKE", null, key));
        verifyNoInteractions(resolver.service);
    }
}
