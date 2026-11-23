package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.CharacterAlignment;
import com.eonmux.chatllm.services.CharacterAlignmentService;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CharacterAlignmentResolverTest {
    CharacterAlignmentResolver resolver;
    UUID owner = UUID.randomUUID();
    String character = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();

    @BeforeEach void setup() {
        resolver = new CharacterAlignmentResolver();
        resolver.authContext = mock(GraphQLAuthContext.class);
        AuthContext auth = mock(AuthContext.class);
        when(auth.getUserId()).thenReturn(owner);
        when(resolver.authContext.requireAuth()).thenReturn(auth);
        resolver.service = mock(CharacterAlignmentService.class);
    }

    @Test void usesAuthenticatedOwnerAndNormalizesTheReason() throws Exception {
        when(resolver.service.set(owner.toString(), character, 70, 60, "Kept a promise", key))
                .thenReturn(Future.succeededFuture(new CharacterAlignment()));
        resolver.setCharacterAlignment(character, 70, 60, " Kept a promise ", key).get();
        verify(resolver.service).set(owner.toString(), character, 70, 60, "Kept a promise", key);
    }

    @Test void adjustsTheUsersOwnAlignmentWhenNoCharacterIsSelected() throws Exception {
        when(resolver.service.adjust(owner.toString(), null, -10, 10, null, key))
                .thenReturn(Future.succeededFuture(new CharacterAlignment()));
        resolver.adjustCharacterAlignment(null, -10, 10, null, key).get();
        verify(resolver.service).adjust(owner.toString(), null, -10, 10, null, key);
    }

    @Test void invalidRequestsNeverTouchTheLedger() {
        assertThrows(Exception.class, () -> resolver.setCharacterAlignment(character, 101, 0, null, key));
        assertThrows(Exception.class, () -> resolver.setCharacterAlignment(character, 0, -101, null, key));
        assertThrows(Exception.class, () -> resolver.setCharacterAlignment("not-a-uuid", 0, 0, null, key));
        assertThrows(Exception.class, () -> resolver.setCharacterAlignment(character, 0, 0, "a".repeat(501), key));
        assertThrows(Exception.class, () -> resolver.setCharacterAlignment(character, 0, 0, null, "invalid"));
        assertThrows(Exception.class, () -> resolver.adjustCharacterAlignment(character, 0, 0, null, key));
        assertThrows(Exception.class, () -> resolver.adjustCharacterAlignment(character, 101, 0, null, key));
        assertThrows(Exception.class, () -> resolver.adjustCharacterAlignment(character, 0, -101, null, key));
        verifyNoInteractions(resolver.service);
    }

    @Test void rejectsUnauthenticatedReadsAndWrites() {
        when(resolver.authContext.requireAuth()).thenThrow(new SecurityException("Authentication required"));
        assertThrows(SecurityException.class, () -> resolver.characterAlignments());
        assertThrows(SecurityException.class, () -> resolver.setCharacterAlignment(character, 0, 0, null, key));
        assertThrows(SecurityException.class, () -> resolver.adjustCharacterAlignment(character, 10, 0, null, key));
        verifyNoInteractions(resolver.service);
    }
}
