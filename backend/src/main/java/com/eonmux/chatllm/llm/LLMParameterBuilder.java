package com.eonmux.chatllm.llm;

import com.eonmux.chatllm.models.Settings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds LLM generation parameters from user settings.
 * Integrates all user-configured LLM settings into API calls.
 */
@Slf4j
public class LLMParameterBuilder {

    /**
     * Build complete LLM parameters from user settings.
     * Falls back to provided defaults if settings are null.
     */
    public static JsonObject buildParameters(
            Settings.LLMSettings llmSettings,
            String prompt,
            JsonObject defaultConfig
    ) {
        if (llmSettings == null) {
            log.warn("No LLM settings provided, using defaults");
            return buildFromDefaults(prompt, defaultConfig);
        }

        JsonObject params = new JsonObject();

        // Required params
        params.put("prompt", prompt);
        params.put("stream", llmSettings.getStream() != null ? llmSettings.getStream() : true);

        // Sampling parameters
        params.put("temperature", llmSettings.getTemperature() != null ? llmSettings.getTemperature() : defaultConfig.getDouble("temperature", 0.7));
        params.put("top_p", llmSettings.getTopP() != null ? llmSettings.getTopP() : defaultConfig.getDouble("topP", 0.9));
        // llama.cpp default top_k is 100 (0 disables). Don't silently change sampling
        // when no setting is provided.
        params.put("top_k", llmSettings.getTopK() != null ? llmSettings.getTopK() : defaultConfig.getInteger("topK", 100));

        // Penalty parameters
        params.put("repeat_penalty", llmSettings.getRepeatPenalty() != null ? llmSettings.getRepeatPenalty() : 1.1);
        params.put("presence_penalty", llmSettings.getPresencePenalty() != null ? llmSettings.getPresencePenalty() : 0.0);
        params.put("frequency_penalty", llmSettings.getFrequencyPenalty() != null ? llmSettings.getFrequencyPenalty() : 0.0);

        // Advanced sampling parameters
        if (llmSettings.getMinP() != null) {
            params.put("min_p", llmSettings.getMinP());
        }
        if (llmSettings.getTfsZ() != null) {
            params.put("tfs_z", llmSettings.getTfsZ());
        }
        if (llmSettings.getTypicalP() != null) {
            params.put("typical_p", llmSettings.getTypicalP());
        }

        // Generation length. Preserve the user's explicit value: in llama.cpp,
        // n_predict = -1 means "until context full" and 0 means "predict nothing".
        // Only remap a missing/absent default to a large cap. Previously any
        // value <= 0 (including the -1 "unlimited" sentinel) was mapped to 1024000,
        // capping generation and breaking the 0/-1 semantics.
        Integer resolvedMaxTokens = llmSettings.getMaxTokens();
        if (resolvedMaxTokens == null) {
            int defaultMax = defaultConfig.getInteger("maxTokens", -1);
            resolvedMaxTokens = defaultMax > 0 ? defaultMax : -1;
        }
        // Only set n_predict to a concrete cap when it's a positive number; pass
        // through -1 so llama.cpp treats it as "until context full".
        params.put("n_predict", llmSettings.getNPredict() != null ? llmSettings.getNPredict() : resolvedMaxTokens);

        // Context settings
        if (llmSettings.getNCtx() != null) {
            params.put("n_ctx", llmSettings.getNCtx());
        }
        if (llmSettings.getNBatch() != null) {
            params.put("n_batch", llmSettings.getNBatch());
        }

        // Mirostat sampling
        if (llmSettings.getMirostat() != null && llmSettings.getMirostat() > 0) {
            params.put("mirostat", llmSettings.getMirostat());
            if (llmSettings.getMirostatTau() != null) {
                params.put("mirostat_tau", llmSettings.getMirostatTau());
            }
            if (llmSettings.getMirostatEta() != null) {
                params.put("mirostat_eta", llmSettings.getMirostatEta());
            }
        }

        // Random seed for reproducibility
        if (llmSettings.getSeed() != null) {
            params.put("seed", llmSettings.getSeed());
        }

        // Stop sequences
        if (llmSettings.getStopSequences() != null && !llmSettings.getStopSequences().isEmpty()) {
            JsonArray stopSeqs = new JsonArray();
            llmSettings.getStopSequences().forEach(stopSeqs::add);
            params.put("stop", stopSeqs);
        }

        // Cache prompt for performance (from config)
        if (defaultConfig.containsKey("cachePrompt")) {
            params.put("cache_prompt", defaultConfig.getBoolean("cachePrompt"));
        }

        log.debug("Built LLM parameters from user settings: temp={}, topP={}, topK={}, maxTokens={}, mirostat={}",
                params.getDouble("temperature"), params.getDouble("top_p"), params.getInteger("top_k"),
                params.getInteger("n_predict"), llmSettings.getMirostat());

        return params;
    }

    /**
     * Build parameters from defaults when no user settings available.
     */
    private static JsonObject buildFromDefaults(String prompt, JsonObject defaultConfig) {
        JsonObject params = new JsonObject();
        params.put("prompt", prompt);
        params.put("stream", true);
        params.put("temperature", defaultConfig.getDouble("temperature", 0.7));
        params.put("top_p", defaultConfig.getDouble("topP", 0.9));
        params.put("top_k", defaultConfig.getInteger("topK", 100));
        params.put("repeat_penalty", 1.1);

        // Preserve -1 (until context full); only cap a positive default.
        int maxTokens = defaultConfig.getInteger("maxTokens", -1);
        params.put("n_predict", maxTokens > 0 ? maxTokens : -1);

        if (defaultConfig.containsKey("cachePrompt")) {
            params.put("cache_prompt", defaultConfig.getBoolean("cachePrompt"));
        }

        return params;
    }

    /**
     * Build parameters for OpenAI-compatible external APIs.
     * Maps llama.cpp-style settings to OpenAI field names.
     */
    public static JsonObject buildOpenAIParameters(
            Settings.LLMSettings llmSettings,
            JsonArray messages,
            String model,
            JsonObject defaultConfig
    ) {
        JsonObject params = new JsonObject();
        String effectiveModel = (model != null && !model.isEmpty()) ? model : "auto";
        params.put("model", effectiveModel);
        params.put("messages", messages);
        params.put("stream", true);

        if (llmSettings == null) {
            params.put("temperature", defaultConfig.getDouble("temperature", 0.7));
            params.put("top_p", defaultConfig.getDouble("topP", 0.9));
            int maxTokens = defaultConfig.getInteger("maxTokens", -1);
            if (maxTokens > 0) {
                params.put("max_tokens", maxTokens);
            }
            return params;
        }

        // Sampling parameters
        params.put("temperature", llmSettings.getTemperature() != null ? llmSettings.getTemperature() : defaultConfig.getDouble("temperature", 0.7));
        params.put("top_p", llmSettings.getTopP() != null ? llmSettings.getTopP() : defaultConfig.getDouble("topP", 0.9));

        // Penalty parameters (OpenAI uses presence_penalty and frequency_penalty)
        if (llmSettings.getPresencePenalty() != null && llmSettings.getPresencePenalty() != 0.0) {
            params.put("presence_penalty", llmSettings.getPresencePenalty());
        }
        if (llmSettings.getFrequencyPenalty() != null && llmSettings.getFrequencyPenalty() != 0.0) {
            params.put("frequency_penalty", llmSettings.getFrequencyPenalty());
        }

        // Generation length - OpenAI uses max_tokens (not n_predict)
        int maxTokens = llmSettings.getMaxTokens() != null ? llmSettings.getMaxTokens() : defaultConfig.getInteger("maxTokens", -1);
        if (maxTokens > 0) {
            params.put("max_tokens", maxTokens);
        }

        // Random seed for reproducibility
        if (llmSettings.getSeed() != null && llmSettings.getSeed() >= 0) {
            params.put("seed", llmSettings.getSeed());
        }

        // Stop sequences
        if (llmSettings.getStopSequences() != null && !llmSettings.getStopSequences().isEmpty()) {
            JsonArray stopSeqs = new JsonArray();
            llmSettings.getStopSequences().forEach(stopSeqs::add);
            params.put("stop", stopSeqs);
        }

        log.debug("Built OpenAI parameters: model={}, temp={}, topP={}, maxTokens={}",
                effectiveModel, params.getDouble("temperature"), params.getDouble("top_p"), params.getInteger("max_tokens"));

        return params;
    }

    /**
     * Build model initialization parameters from settings.
     * Used when loading/configuring the model.
     */
    public static JsonObject buildModelConfig(Settings.LLMSettings llmSettings, JsonObject defaultConfig) {
        JsonObject config = new JsonObject();

        // Context size
        int nCtx = llmSettings.getNCtx() != null ? llmSettings.getNCtx() : defaultConfig.getInteger("contextSize", 4096);
        config.put("n_ctx", nCtx);

        // Batch size
        if (llmSettings.getNBatch() != null) {
            config.put("n_batch", llmSettings.getNBatch());
        }

        // Thread configuration
        if (llmSettings.getNThreads() != null) {
            config.put("n_threads", llmSettings.getNThreads());
        }

        // GPU configuration
        if (llmSettings.getNGpuLayers() != null) {
            config.put("n_gpu_layers", llmSettings.getNGpuLayers());
        }
        if (llmSettings.getMainGpu() != null) {
            config.put("main_gpu", llmSettings.getMainGpu());
        }

        // Model path
        if (llmSettings.getModel() != null && !llmSettings.getModel().isEmpty()) {
            config.put("model", llmSettings.getModel());
        }

        log.info("Built model config: ctx={}, gpuLayers={}, threads={}, mainGpu={}",
                config.getInteger("n_ctx"), config.getInteger("n_gpu_layers"),
                config.getInteger("n_threads"), config.getInteger("main_gpu"));

        return config;
    }

    /**
     * Merge user settings into request parameters.
     * Used when user overrides are provided in the request.
     */
    public static JsonObject mergeUserOverrides(JsonObject baseParams, JsonObject userOverrides) {
        if (userOverrides == null || userOverrides.isEmpty()) {
            return baseParams;
        }

        JsonObject merged = baseParams.copy();

        // Allow overriding these parameters
        if (userOverrides.containsKey("temperature")) {
            merged.put("temperature", userOverrides.getDouble("temperature"));
        }
        if (userOverrides.containsKey("maxTokens")) {
            // Preserve 0/-1 (predict nothing / until context full); only remap
            // an absent value to the cap, which is handled by the default path.
            merged.put("n_predict", userOverrides.getInteger("maxTokens"));
        }
        if (userOverrides.containsKey("topP")) {
            merged.put("top_p", userOverrides.getDouble("topP"));
        }
        if (userOverrides.containsKey("topK")) {
            merged.put("top_k", userOverrides.getInteger("topK"));
        }

        log.debug("Merged user overrides into parameters");
        return merged;
    }
}
