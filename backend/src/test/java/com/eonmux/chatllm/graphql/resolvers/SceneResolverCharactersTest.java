package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.testing.SqlTestSupport;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SceneResolverCharactersTest {

    @Test void sceneCastCarriesTheFieldsTheClientRenders() throws Exception {
        String first = UUID.randomUUID().toString();
        SceneResolver resolver = new SceneResolver();
        resolver.mysqlPool = SqlTestSupport.pool((sql, parameters) -> {
            assertTrue(sql.contains("JOIN `character`"), sql);
            return SqlTestSupport.rows(
                    SqlTestSupport.row(Map.of(
                            "characterUUID", first, "name", "Elara Vance", "avatar", "/a.png")),
                    SqlTestSupport.row(Map.of(
                            "characterUUID", UUID.randomUUID().toString(),
                            "name", "Jax Thorne", "avatar", "")));
        });

        Method method = SceneResolver.class.getDeclaredMethod("loadSceneCharacters", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Character> cast = (List<Character>) ((Uni<?>) method.invoke(resolver, "scene-1"))
                .await().indefinitely();

        assertEquals(2, cast.size());
        assertEquals(first, cast.get(0).getId().toString());
        assertEquals("Elara Vance", cast.get(0).getName());
        assertEquals("/a.png", cast.get(0).getAvatar());
        assertEquals("Jax Thorne", cast.get(1).getName());
    }
}
