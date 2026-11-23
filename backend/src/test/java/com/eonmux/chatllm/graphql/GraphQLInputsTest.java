package com.eonmux.chatllm.graphql;

import com.eonmux.chatllm.models.inputs.UpdateProfileInput;
import com.eonmux.chatllm.models.inputs.UpdateConversationInput;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.smallrye.graphql.api.Context;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphQLInputsTest {
    @Test
    void partialProfileDoesNotOverwriteOmittedIdentityFields() {
        UpdateProfileInput input = new UpdateProfileInput();
        input.setDisplayName("Updated name");
        Map<String, Object> result = GraphQLInputs.suppliedInput(input,
                context(Map.of("displayName", "Updated name")));
        assertEquals(Map.of("displayName", "Updated name"), result);
        assertFalse(result.containsKey("username"));
        assertFalse(result.containsKey("email"));
    }

    @Test
    void explicitNullAndFalseRemainDistinctFromOmittedFields() {
        Map<String, Object> supplied = new LinkedHashMap<>();
        supplied.put("description", null);
        supplied.put("isPinned", false);
        supplied.put("tags", java.util.List.of());
        Map<String, Object> result = GraphQLInputs.suppliedInput(new UpdateConversationInput(), context(supplied));
        assertTrue(result.containsKey("description"));
        assertNull(result.get("description"));
        assertEquals(false, result.get("isPinned"));
        assertEquals(java.util.List.of(), result.get("tags"));
        assertFalse(result.containsKey("title"));
        assertFalse(result.containsKey("isArchived"));
    }

    @Test
    void directJavaCallersOnlyUpdateNonNullFields() {
        UpdateProfileInput input = new UpdateProfileInput();
        input.setBio("");
        assertEquals(Map.of("bio", ""), GraphQLInputs.suppliedInput(input, null));
    }

    @SuppressWarnings("unchecked")
    private Instance<Context> context(Map<String, Object> input) {
        DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .arguments(Map.of("input", input)).build();
        Context context = mock(Context.class);
        when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(environment);
        Instance<Context> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(context);
        return instance;
    }
}
