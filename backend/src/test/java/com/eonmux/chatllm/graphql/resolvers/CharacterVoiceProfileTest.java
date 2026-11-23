package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.models.ConversationCharacter;
import com.eonmux.chatllm.models.Message;
import io.vertx.mutiny.sqlclient.Pool;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.eonmux.chatllm.testing.SqlTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class CharacterVoiceProfileTest {
    private final UUID id = UUID.randomUUID();
    private final Pool database = pool((sql, parameters) -> rows(row(Map.of(
            "characterUUID", id.toString(), "name", "Elara Thorne", "personality", "Impulsive and fiercely loyal.",
            "gender", "Woman", "pronouns", "she/her", "ageYears", 24))));

    private void assertVoiceProfile(Character character) {
        assertEquals(id, character.getId());
        assertEquals("Impulsive and fiercely loyal.", character.getPersonality());
        assertEquals("Woman", character.getGender());
        assertEquals("she/her", character.getPronouns());
        assertEquals(24, character.getAgeYears());
    }

    @Test void savedMessageRetainsVocalIdentity() {
        MessageResolver resolver = new MessageResolver();
        resolver.mysqlPool = database;
        Message message = new Message();
        message.setCharacterId(id);
        assertVoiceProfile(resolver.character(message).await().indefinitely());
    }

    @Test void singleConversationRetainsVocalIdentity() {
        ConversationResolver resolver = new ConversationResolver();
        resolver.mysqlPool = database;
        Conversation conversation = new Conversation();
        conversation.setCharacterId(id);
        assertVoiceProfile(resolver.character(conversation).await().indefinitely());
    }

    @Test void joinedCharacterRetainsPersonalityAndVocalIdentity() {
        GroupChatResolver resolver = new GroupChatResolver();
        resolver.mysqlPool = database;
        ConversationCharacter membership = new ConversationCharacter();
        membership.setCharacterId(id);
        assertVoiceProfile(resolver.character(membership).await().indefinitely());
    }
}
