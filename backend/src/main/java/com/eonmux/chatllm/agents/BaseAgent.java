package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.services.AgentModelConfigService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public abstract class BaseAgent {
    protected final String agentName;
    protected final Vertx vertx;
    protected final Pool mysqlPool;
    protected final AppConfig config;
    protected final AgentModelConfigService modelConfigService;
    private volatile WebClient sharedClient;

    protected BaseAgent(String agentName, Vertx vertx, Pool mysqlPool, AppConfig config,
                        AgentModelConfigService modelConfigService) {
        this.agentName = agentName;
        this.vertx = vertx;
        this.mysqlPool = mysqlPool;
        this.config = config;
        this.modelConfigService = modelConfigService;
    }

    /**
     * Get or create a shared WebClient instance for LLM calls.
     */
    private WebClient getOrCreateClient() {
        if (sharedClient == null) {
            synchronized (this) {
                if (sharedClient == null) {
                    String host;
                    int port;
                    boolean useSsl = false;

                    if (config.isLlmExternalServer()) {
                        String baseUrl = config.getLlmExternalBaseUrl();
                        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                        java.net.URI uri = java.net.URI.create(baseUrl);
                        host = uri.getHost();
                        useSsl = "https".equals(uri.getScheme());
                        port = uri.getPort();
                        if (port == -1) port = useSsl ? 443 : 80;
                    } else {
                        host = config.getLlmHost();
                        port = config.getLlmPort();
                    }

                    sharedClient = WebClient.create(vertx, new WebClientOptions()
                        .setDefaultHost(host)
                        .setDefaultPort(port)
                        .setSsl(useSsl)
                        .setTrustAll(useSsl)
                        .setConnectTimeout(30000)
                        .setIdleTimeout(120)
                        .setMaxPoolSize(4));
                }
            }
        }
        return sharedClient;
    }

    public String getAgentName() {
        return agentName;
    }

    /**
     * Resolve which model this agent should use for a given conversation.
     * Override chain: per-conversation DB config -> AppConfig env vars -> conversation's main model.
     */
    protected Uni<String> resolveModel(String conversationId) {
        return modelConfigService.getConfig(conversationId)
            .map(agentConfig -> {
                if (agentConfig != null) {
                    String override = agentConfig.getModelForAgent(agentName);
                    if (override != null && !override.isEmpty()) {
                        return override;
                    }
                    if (agentConfig.getDefaultModel() != null && !agentConfig.getDefaultModel().isEmpty()) {
                        return agentConfig.getDefaultModel();
                    }
                }
                // Fall back to AppConfig defaults
                String appConfigDefault = getAppConfigModelDefault();
                if (appConfigDefault != null && !appConfigDefault.isEmpty()) {
                    return appConfigDefault;
                }
                // Ultimate fallback: empty string means "use the conversation's main model"
                return "";
            })
            .onFailure().recoverWithItem("");
    }

    /**
     * Get the AppConfig default model for this agent.
     */
    private String getAppConfigModelDefault() {
        return switch (agentName) {
            case "highlighter" -> config.getAgentHighlighterModel();
            case "muse" -> config.getAgentMuseModel();
            case "narrator" -> config.getAgentNarratorModel();
            case "accountant" -> config.getAgentAccountantModel();
            case "skeptic" -> config.getAgentSkepticModel();
            case "caster" -> config.getAgentCasterModel();
            default -> config.getAgentDefaultModel();
        };
    }

    /**
     * Make a non-streaming LLM completion call using the default model.
     */
    protected Uni<JsonObject> callLlm(String systemPrompt, String userPrompt,
                                          float temperature, int maxTokens) {
        return callLlm(systemPrompt, userPrompt, temperature, maxTokens, null);
    }

    /**
     * Make a non-streaming LLM completion call with an explicit model override.
     * If resolvedModel is non-null and non-empty, it takes priority over config defaults.
     *
     * In local mode, routes through LLMVerticle event bus so the ModelServerPool
     * resolves the correct dynamic port. In external mode, calls the API directly.
     */
    protected Uni<JsonObject> callLlm(String systemPrompt, String userPrompt,
                                          float temperature, int maxTokens, String resolvedModel) {
        return callLlm(systemPrompt, userPrompt, temperature, maxTokens, resolvedModel, false);
    }

    /**
     * Call the model for a JSON-only response. Reasoning models can spend the
     * whole completion budget before emitting content, which leaves an empty
     * body. External providers that support chat_template_kwargs receive an
     * explicit request to skip the reasoning pass for these calls.
     */
    protected Uni<JsonObject> callLlmJson(String systemPrompt, String userPrompt,
                                          float temperature, int maxTokens, String resolvedModel) {
        return callLlm(systemPrompt, userPrompt, temperature, maxTokens, resolvedModel, true);
    }

    /**
     * Make a non-streaming call for prose, with the model's reasoning pass
     * disabled when the provider supports it. A reasoning pass spends the
     * completion budget before the prose starts; the narrator's cap was
     * exhausted by it, which left the opening scene cut off mid-sentence.
     */
    protected Uni<JsonObject> callLlmProse(String systemPrompt, String userPrompt,
                                           float temperature, int maxTokens, String resolvedModel) {
        return callLlm(systemPrompt, userPrompt, temperature, maxTokens, resolvedModel,
                true, config.isLlmProseDisableThinking());
    }

    private Uni<JsonObject> callLlm(String systemPrompt, String userPrompt,
                                    float temperature, int maxTokens, String resolvedModel,
                                    boolean disableThinking) {
        return callLlm(systemPrompt, userPrompt, temperature, maxTokens, resolvedModel,
                disableThinking, config.isLlmAgentJsonDisableThinking());
    }

    private Uni<JsonObject> callLlm(String systemPrompt, String userPrompt,
                                    float temperature, int maxTokens, String resolvedModel,
                                    boolean disableThinking, boolean featureEnabled) {
        JsonArray messages = new JsonArray()
            .add(new JsonObject().put("role", "system").put("content", systemPrompt))
            .add(new JsonObject().put("role", "user").put("content", userPrompt));

        JsonObject params = new JsonObject()
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false);

        // Apply resolved model if provided (per-agent/per-conversation override)
        if (resolvedModel != null && !resolvedModel.isEmpty()) {
            params.put("model", resolvedModel);
        }

        // The local llama route ignores chat_template_kwargs, so only ask the
        // external provider to skip its reasoning pass.
        boolean skipThinking = disableThinking
            && featureEnabled
            && config.isLlmExternalServer();
        if (skipThinking) {
            params.put("chat_template_kwargs", new JsonObject().put("enable_thinking", false));
        }

        log.info("[{}] LLM request -> model={}, temp={}, max_tokens={}, thinking={}", agentName,
            params.getString("model", "(default)"), temperature, maxTokens,
            skipThinking ? "disabled" : "default");
        log.debug("[{}] LLM system prompt (first 500 chars): {}", agentName,
            systemPrompt.length() > 500 ? systemPrompt.substring(0, 500) + "..." : systemPrompt);
        log.debug("[{}] LLM user prompt (first 500 chars): {}", agentName,
            userPrompt.length() > 500 ? userPrompt.substring(0, 500) + "..." : userPrompt);

        // Local mode: route through LLMVerticle to use the correct model server port
        if (!config.isLlmExternalServer()) {
            io.vertx.core.eventbus.DeliveryOptions opts =
                new io.vertx.core.eventbus.DeliveryOptions().setSendTimeout(120000); // 2 minutes
            return vertx.eventBus().<JsonObject>request("llm.agent.completion", params, opts)
                .map(reply -> {
                    JsonObject body = reply.body();
                    String content = extractContent(body);
                    JsonObject usage = extractUsage(body);
                    log.info("[{}] LLM response <- tokens(in={}, out={}), content length={}",
                        agentName,
                        usage.getInteger("prompt_tokens", -1),
                        usage.getInteger("completion_tokens", -1),
                        content != null ? content.length() : 0);
                    log.debug("[{}] LLM response content (first 500 chars): {}", agentName,
                        content != null && content.length() > 500
                            ? content.substring(0, 500) + "..." : content);
                    return body;
                });
        }

        // External mode: direct HTTP call to the configured API endpoint
        String path = "/v1/chat/completions";

        String baseUrl = config.getLlmExternalBaseUrl();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        java.net.URI uri = java.net.URI.create(baseUrl);
        path = (uri.getPath() != null ? uri.getPath() : "") + "/v1/chat/completions";

        // Only apply external model if no per-agent override was set
        if (!params.containsKey("model")) {
            String model = config.getLlmExternalModel();
            if (model != null && !model.isEmpty()) {
                params.put("model", model);
            }
        }

        WebClient client = getOrCreateClient();

        var request = client.post(path)
            .timeout(config.getLlmRequestTimeout());

        // Add authorization header for external provider
        String apiKey = config.getLlmExternalApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            request.putHeader("Authorization", "Bearer " + apiKey);
        }

        return request
            .sendJson(params)
            .map(response -> {
                if (response.statusCode() == 200) {
                    JsonObject body = response.bodyAsJsonObject();
                    String content = extractContent(body);
                    JsonObject usage = extractUsage(body);
                    log.info("[{}] LLM response <- tokens(in={}, out={}), content length={}",
                        agentName,
                        usage.getInteger("prompt_tokens", -1),
                        usage.getInteger("completion_tokens", -1),
                        content != null ? content.length() : 0);
                    log.debug("[{}] LLM response content (first 500 chars): {}", agentName,
                        content != null && content.length() > 500
                            ? content.substring(0, 500) + "..." : content);
                    return body;
                }
                String errBody = response.bodyAsString();
                log.error("[{}] LLM call failed: status={}, body={}", agentName,
                    response.statusCode(), errBody);
                throw new RuntimeException("LLM call failed with status " + response.statusCode() +
                    ": " + errBody);
            });
    }

    /** All judgment calls share strict parsing and exactly one schema-repair attempt. */
    protected Uni<JsonObject> callJudgment(String system, String prompt, int tokens, String model,
                                           java.util.function.Predicate<JsonObject> schema) {
        return callJudgmentAttempt(system, prompt, tokens, model, schema, false);
    }

    private Uni<JsonObject> callJudgmentAttempt(String system, String prompt, int tokens, String model,
                                               java.util.function.Predicate<JsonObject> schema, boolean retry) {
        return callLlmJson(system, prompt, 0.1f, tokens, model).chain(response -> {
            boolean valid;
            try { valid = schema.test(JudgmentProtocol.object(extractContent(response))); }
            catch (RuntimeException invalid) { valid = false; }
            if (valid) return Uni.createFrom().item(response);
            if (!retry) return callJudgmentAttempt(system,
                prompt + "\nRETRY: Return exactly one complete JSON object matching ONLY the requested decision schema. No extra keys, arrays, prose, duplicate keys or contradictory payloads.",
                tokens, model, schema, true);
            return Uni.createFrom().failure(new IllegalArgumentException(agentName + " judgment did not match its single-result contract"));
        });
    }

    /**
     * Extract the text content from a chat completions response.
     */
    protected String extractContent(JsonObject response) {
        try {
            return response.getJsonArray("choices")
                .getJsonObject(0)
                .getJsonObject("message")
                .getString("content", "");
        } catch (Exception e) {
            // Try legacy /completion format
            return response.getString("content", "");
        }
    }

    /**
     * Extract token usage from response.
     */
    protected JsonObject extractUsage(JsonObject response) {
        try {
            JsonObject usage = response.getJsonObject("usage");
            return usage != null ? usage : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    /**
     * Parse and validate a JSON object from LLM response content.
     * Strips markdown code fences and whitespace, then parses as JSON.
     * If parsing fails, attempts to repair truncated JSON by closing unclosed brackets.
     * Returns null if parsing still fails after repair attempt.
     */
    protected JsonObject parseJsonResponse(String content) {
        if (content == null || content.isBlank()) return null;
        String cleaned = content
            .replaceAll("(?s)```json\\s*", "")
            .replaceAll("(?s)```\\s*", "")
            .trim();
        try {
            return new JsonObject(cleaned);
        } catch (Exception e) {
            // Attempt to repair truncated JSON (common when hitting max_tokens)
            JsonObject repaired = attemptJsonRepair(cleaned);
            if (repaired != null) {
                log.info("[{}] Repaired truncated JSON response ({} chars)", agentName, cleaned.length());
                return repaired;
            }
            log.warn("[{}] Failed to parse JSON response ({} chars): {}", agentName, cleaned.length(),
                cleaned.length() > 300 ? cleaned.substring(0, 300) + "..." : cleaned);
            return null;
        }
    }

    /**
     * Attempt to repair truncated JSON by closing unclosed brackets and braces.
     * Handles the common case where LLM output is cut off at max_tokens.
     */
    private JsonObject attemptJsonRepair(String json) {
        if (json == null || json.isEmpty() || json.charAt(0) != '{') return null;

        // Track unclosed brackets/braces, ignoring those inside strings
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') braces++;
            else if (c == '}') braces--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
        }

        if (braces == 0 && brackets == 0) return null; // Not truncated, just invalid

        // Truncate at the last complete value boundary (after last complete key-value pair)
        // Find the last comma, closing bracket, or closing brace before truncation
        int lastSafe = -1;
        inString = false;
        escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == ',' || c == '}' || c == ']') lastSafe = i;
        }

        if (lastSafe <= 0) return null;

        // Cut at the last safe point (removing trailing comma if present)
        String truncated = json.substring(0, lastSafe);
        if (truncated.endsWith(",")) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }

        // Re-count what needs closing
        braces = 0; brackets = 0; inString = false; escaped = false;
        for (int i = 0; i < truncated.length(); i++) {
            char c = truncated.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') braces++;
            else if (c == '}') braces--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
        }

        // Close unclosed brackets then braces
        StringBuilder repaired = new StringBuilder(truncated);
        for (int i = 0; i < brackets; i++) repaired.append(']');
        for (int i = 0; i < braces; i++) repaired.append('}');

        try {
            return new JsonObject(repaired.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse a JSON object with required fields validation.
     * Returns null and logs a warning if any required field is missing.
     */
    protected JsonObject parseJsonResponseStrict(String content, String... requiredFields) {
        JsonObject json = parseJsonResponse(content);
        if (json == null) return null;
        for (String field : requiredFields) {
            if (!json.containsKey(field)) {
                log.warn("[{}] JSON response missing required field '{}': {}", agentName, field,
                    json.encode().length() > 300 ? json.encode().substring(0, 300) + "..." : json.encode());
                return null;
            }
        }
        return json;
    }

    /**
     * Subclasses implement this to perform their specific task.
     * The resolvedModel is passed explicitly to avoid race conditions when
     * multiple agents run in parallel on the same AgentContext.
     */
    protected abstract Uni<AgentResult> execute(AgentContext context, String resolvedModel);

    /**
     * Main entry point -- resolves model, executes, logs to agent_run table.
     */
    public Uni<AgentResult> run(AgentContext context) {
        long startTime = System.currentTimeMillis();
        String conversationId = context.getConversationId();

        if (context.getProgressReporter() != null) {
            String label = switch (agentName) {
                case "highlighter" -> "Reviewing the conversation…";
                case "encounter_decision" -> "Checking for an encounter…";
                case "travel_decision" -> "Resolving travel…";
                case "interaction_decision" -> "Resolving the interaction…";
                case "accountant" -> "Updating the story state…";
                case "muse" -> "Preparing the next scene…";
                case "narrator" -> "Narrator is writing the scene…";
                default -> "Preparing the story…";
            };
            context.getProgressReporter().accept(agentName,label);
        }
        return resolveModel(conversationId)
            .chain(model -> {
                log.info("[{}] Running with model: {}", agentName,
                    model.isEmpty() ? "(default)" : model);
                return execute(context, model)
                    .map(result -> {
                        result.setModelUsed(model);
                        result.setDurationMs(System.currentTimeMillis() - startTime);
                        result.setAgentName(agentName);
                        if (result.getStatus() == null) result.setStatus("success");
                        log.info("[{}] Completed in {}ms, status={}, output length={}",
                            agentName, result.getDurationMs(), result.getStatus(),
                            result.getContent() != null ? result.getContent().length() : 0);
                        if (result.getStructuredOutput() != null) {
                            log.info("[{}] Structured output keys: {}", agentName,
                                result.getStructuredOutput().fieldNames());
                        }
                        return result;
                    });
            })
            .chain(result -> {
                // Skip base log if the subclass already logged (e.g. SkepticAgent.evaluate())
                if (result.getAnnotations() != null && result.getAnnotations().containsKey("_skipBaseLog")) {
                    result.getAnnotations().remove("_skipBaseLog");
                    return Uni.createFrom().item(result);
                }
                return logRun(conversationId, context.getTriggerMessageId(), result)
                    .map(v -> result);
            })
            .onFailure().recoverWithUni(err -> {
                log.error("[{}] Failed: {}", agentName, err.getMessage());
                AgentResult errorResult = AgentResult.error(agentName, err.getMessage());
                errorResult.setDurationMs(System.currentTimeMillis() - startTime);
                return logRun(conversationId, context.getTriggerMessageId(), errorResult)
                    .map(v -> errorResult);
            });
    }

    /**
     * Log agent run to database.
     */
    private Uni<Void> logRun(String conversationId, String triggerMessageId, AgentResult result) {
        return logRun(conversationId, triggerMessageId, result, agentName);
    }

    protected Uni<Void> logRun(String conversationId, String triggerMessageId, AgentResult result, String step) {
        if (mysqlPool == null || conversationId == null) return Uni.createFrom().voidItem();
        String runId = UUID.randomUUID().toString();
        String insert = """
            INSERT INTO agent_run (runId, conversationId, triggerMessageId, agentName, modelUsed,
                inputTokens, outputTokens, durationMs, output, status, retryCount, errorMessage)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        String outputJson = result.getStructuredOutput() != null
            ? result.getStructuredOutput().encode()
            : new JsonObject().put("content", result.getContent()).encode();

        Tuple params = Tuple.tuple()
            .addValue(runId).addValue(conversationId).addValue(triggerMessageId).addValue(step)
            .addValue(result.getModelUsed()).addValue(result.getInputTokens()).addValue(result.getOutputTokens())
            .addValue(result.getDurationMs()).addValue(outputJson).addValue(result.getStatus())
            .addValue(result.getRetryCount() != null ? result.getRetryCount() : 0)
            .addValue("error".equals(result.getStatus()) ? result.getContent() : null);

        return mysqlPool.preparedQuery(insert)
            .execute(params)
            .replaceWithVoid()
            .onFailure().recoverWithUni(err -> {
                log.warn("[{}] Failed to log run: {}", agentName, err.getMessage());
                return Uni.createFrom().voidItem();
            });
    }
}
