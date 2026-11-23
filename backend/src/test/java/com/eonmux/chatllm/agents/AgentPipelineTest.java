package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.services.AgentModelConfigService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AgentPipelineTest.TestProfileConfig.class)
class AgentPipelineTest {

    @Mock
    Pool mysqlPool;

    @Inject
    Vertx vertx;

    @Inject
    AppConfig config;

    @Mock
    AgentModelConfigService modelConfigService;

    @BeforeEach
    void initMocks() {
        MockitoAnnotations.openMocks(this);
    }

    // ---- Test Profile ----

    public static class TestProfileConfig implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "chatllm.llm.enabled", "false",
                "chatllm.llm.mock-mode", "true",
                "chatllm.rag.enabled", "false",
                "chatllm.agent.skeptic-narration-gate-enabled", "false",
                "chatllm.agent.skeptic-character-gate-enabled", "false",
                "chatllm.agent.casting-enabled", "false"
            );
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of();
        }

        @Override
        public boolean disableGlobalTestResources() {
            return true;
        }
    }

    // ---- Test Agent Subclass ----

    private static class TestAgent extends BaseAgent {
        public TestAgent(Vertx vertx, Pool pool, AppConfig config, AgentModelConfigService modelConfigService) {
            super("test", vertx, pool, config, modelConfigService);
        }

        @Override
        public Uni<AgentResult> execute(AgentContext context, String resolvedModel) {
            return Uni.createFrom().item(AgentResult.builder().status("ok").content("test").build());
        }

        public JsonObject parseJsonResponsePublic(String content) {
            return parseJsonResponse(content);
        }
    }

    // ===== Tests =====

    @Test
    void testPipelineConstruction() {
        AgentPipeline pipeline = new AgentPipeline(
            vertx, mysqlPool, config, modelConfigService,
            null, null, null, null, null, null, null, null, null, null, null
        );
        assertNotNull(pipeline);
    }

    @Test
    void testHighlighterSkipsEmptyMessages() {
        HighlighterAgent highlighter = new HighlighterAgent(vertx, mysqlPool, config, modelConfigService);
        AgentContext context = AgentContext.builder()
            .conversationId("test-conv-id")
            .triggerMessageId("test-msg-id")
            .recentMessages(new JsonArray())
            .build();

        AgentResult result = highlighter.execute(context, "").await().indefinitely();

        assertEquals("skipped", result.getStatus());
        assertEquals("No messages to summarize", result.getContent());
    }

    @Test
    void testAgentResultBuilder() {
        JsonObject structured = new JsonObject().put("key", "value");
        AgentResult result = AgentResult.builder()
            .agentName("test-agent")
            .content("test content")
            .structuredOutput(structured)
            .status("success")
            .modelUsed("gpt-4")
            .inputTokens(100)
            .outputTokens(50)
            .durationMs(500L)
            .retryCount(0)
            .build();

        assertEquals("test-agent", result.getAgentName());
        assertEquals("test content", result.getContent());
        assertEquals(structured, result.getStructuredOutput());
        assertEquals("success", result.getStatus());
        assertTrue(result.isSuccess());
        assertEquals("gpt-4", result.getModelUsed());
        assertEquals(100, result.getInputTokens());
        assertEquals(50, result.getOutputTokens());
        assertEquals(500L, result.getDurationMs());
        assertEquals(0, result.getRetryCount());
    }

    @Test
    void testPipelineResultBuilder() {
        AgentResult hlResult = AgentResult.builder().agentName("highlighter").status("success").build();
        AgentResult museResult = AgentResult.builder().agentName("muse").status("success").build();
        AgentResult acctResult = AgentResult.builder().agentName("accountant").status("success").build();
        AgentResult narResult = AgentResult.builder().agentName("narrator").status("success").build();
        AgentResult skepticNarResult = AgentResult.builder().agentName("skeptic:narration").status("success").build();
        AgentResult skepticCharResult = AgentResult.builder().agentName("skeptic:character").status("success").build();

        PipelineResult result = PipelineResult.builder()
            .narration("The hero enters the dark forest.")
            .characterResponse("I draw my sword.")
            .highlighterResult(hlResult)
            .museResult(museResult)
            .accountantResult(acctResult)
            .narratorResult(narResult)
            .skepticNarrationResult(skepticNarResult)
            .skepticCharacterResult(skepticCharResult)
            .totalDurationMs(1500L)
            .failed(false)
            .build();

        assertEquals("The hero enters the dark forest.", result.getNarration());
        assertEquals("I draw my sword.", result.getCharacterResponse());
        assertSame(hlResult, result.getHighlighterResult());
        assertSame(museResult, result.getMuseResult());
        assertSame(acctResult, result.getAccountantResult());
        assertSame(narResult, result.getNarratorResult());
        assertSame(skepticNarResult, result.getSkepticNarrationResult());
        assertSame(skepticCharResult, result.getSkepticCharacterResult());
        assertEquals(1500L, result.getTotalDurationMs());
        assertFalse(result.isFailed());
        assertTrue(result.isSuccess());
        assertTrue(result.hasNarration());
    }

    @Test
    void testBaseAgentParseJsonResponse() {
        TestAgent agent = new TestAgent(vertx, mysqlPool, config, modelConfigService);

        JsonObject result = agent.parseJsonResponsePublic("{\"key\": \"value\"}");
        assertNotNull(result);
        assertEquals("value", result.getString("key"));

        assertNull(agent.parseJsonResponsePublic(null));
        assertNull(agent.parseJsonResponsePublic("   "));
        assertNull(agent.parseJsonResponsePublic(""));
    }

    @Test
    void testAttemptJsonRepair() {
        TestAgent agent = new TestAgent(vertx, mysqlPool, config, modelConfigService);

        JsonObject result = agent.parseJsonResponsePublic("{\"key\": \"value\", \"num\": 42}");
        assertNotNull(result);
        assertEquals("value", result.getString("key"));
        assertEquals(42, result.getInteger("num"));

        // Truncated JSON missing closing brace (repair discards incomplete trailing pair)
        result = agent.parseJsonResponsePublic("{\"key\": \"value\", \"num\": 42");
        assertNotNull(result, "Should repair truncated JSON");
        assertEquals("value", result.getString("key"));

        // Markdown code fences
        result = agent.parseJsonResponsePublic("```json\n{\"key\": \"value\"}\n```");
        assertNotNull(result);
        assertEquals("value", result.getString("key"));

        // Nested truncated JSON (repair discards incomplete trailing pair)
        result = agent.parseJsonResponsePublic("{\"outer\": {\"inner\": \"value\"}, \"other\": 1");
        assertNotNull(result, "Should repair truncated nested JSON");
        assertEquals("value", result.getJsonObject("outer").getString("inner"));

        // Truncated with array (repair discards incomplete trailing pair)
        result = agent.parseJsonResponsePublic("{\"items\": [1, 2, 3], \"name\": \"test\"");
        assertNotNull(result, "Should repair JSON with truncated array");
        assertEquals(3, result.getJsonArray("items").size());

        assertNull(agent.parseJsonResponsePublic("{\"key\": \"value\"} some extra text"));

        assertNull(agent.parseJsonResponsePublic("not json at all"));
        assertNull(agent.parseJsonResponsePublic("[1, 2, 3]"));
    }

    @Test
    void testAgentResultSkippedAndError() {
        AgentResult skipped = AgentResult.skipped("highlighter", "No messages");
        assertEquals("highlighter", skipped.getAgentName());
        assertEquals("skipped", skipped.getStatus());
        assertEquals("No messages", skipped.getContent());

        AgentResult error = AgentResult.error("muse", "Connection failed");
        assertEquals("muse", error.getAgentName());
        assertEquals("error", error.getStatus());
        assertEquals("Connection failed", error.getContent());
    }

    @Test
    void testAgentResultAnnotations() {
        AgentResult result = AgentResult.builder().agentName("test").status("success").build();
        result.addAnnotation("key1", "value1");
        result.addAnnotation("key2", "value2");
        assertEquals("value1", result.getAnnotations().get("key1"));
        assertEquals("value2", result.getAnnotations().get("key2"));
    }

    @Test
    void testPipelineResultWarnings() {
        PipelineResult result = PipelineResult.builder().build();
        assertFalse(result.hasNarration());
        assertTrue(result.isSuccess());
        result.addWarning("Something went wrong");
        assertEquals(1, result.getWarnings().size());
        assertEquals("Something went wrong", result.getWarnings().get(0));
    }

    @Test
    void testPipelineResultFailed() {
        PipelineResult result = PipelineResult.builder()
            .failed(true)
            .errorMessage("Pipeline crashed")
            .build();
        assertTrue(result.isFailed());
        assertFalse(result.isSuccess());
        assertEquals("Pipeline crashed", result.getErrorMessage());
    }
}
