package com.eonmux.chatllm.config;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Application configuration for Veyloria Quarkus backend.
 * All values are sourced from application.properties / environment variables
 * via MicroProfile Config. Quarkus handles the config-source priority
 * (env vars > system props > application.properties).
 */
@Getter
@Slf4j
@ApplicationScoped
public class AppConfig {

    // ---- Server ----

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8300")
    int httpPort;

    @ConfigProperty(name = "quarkus.http.host", defaultValue = "0.0.0.0")
    String host;

    @ConfigProperty(name = "chatllm.development-mode", defaultValue = "true")
    boolean developmentMode;

    @ConfigProperty(name = "chatllm.session-timeout", defaultValue = "3600000")
    long sessionTimeout;

    @ConfigProperty(name = "quarkus.http.body.max-body-size", defaultValue = "10M")
    String maxBodySizeStr;

    @ConfigProperty(name = "chatllm.uploads-directory", defaultValue = "/tmp/uploads")
    String uploadsDirectory;

    @ConfigProperty(name = "chatllm.environment", defaultValue = "development")
    String environment;

    @ConfigProperty(name = "chatllm.app-version", defaultValue = "3.0.0")
    String appVersion;

    // ---- Redis ----

    @ConfigProperty(name = "quarkus.redis.hosts", defaultValue = "redis://localhost:6379")
    String redisHosts;

    @ConfigProperty(name = "quarkus.redis.password")
    Optional<String> redisPassword;
    public String getRedisPassword() { return redisPassword != null ? redisPassword.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.redis.database", defaultValue = "0")
    int redisDatabase;

    @ConfigProperty(name = "quarkus.redis.max-pool-size", defaultValue = "10")
    int redisPoolSize;

    // ---- JWT ----

    @ConfigProperty(name = "chatllm.jwt.secret", defaultValue = "change-this-secret-in-production")
    String jwtSecret;

    @ConfigProperty(name = "chatllm.jwt.issuer", defaultValue = "chatllm")
    String jwtIssuer;

    @ConfigProperty(name = "chatllm.jwt.access-token-expiry", defaultValue = "14400000")
    long jwtAccessTokenExpiry;

    @ConfigProperty(name = "chatllm.jwt.refresh-token-expiry", defaultValue = "604800000")
    long jwtRefreshTokenExpiry;

    @ConfigProperty(name = "chatllm.jwt.algorithm", defaultValue = "HS256")
    String jwtAlgorithm;

    // ---- LLM Server basics ----

    @ConfigProperty(name = "chatllm.llm.enabled", defaultValue = "true")
    boolean llmEnabled;

    @ConfigProperty(name = "chatllm.llm.mock-mode", defaultValue = "false")
    boolean llmMockMode;

    @ConfigProperty(name = "chatllm.llm.external-server", defaultValue = "false")
    boolean llmExternalServer;

    @ConfigProperty(name = "chatllm.llm.external-base-url", defaultValue = "http://localhost:8080")
    String llmExternalBaseUrl;

    @ConfigProperty(name = "chatllm.llm.roster-disable-thinking", defaultValue = "true")
    boolean llmRosterDisableThinking;

    @ConfigProperty(name = "chatllm.llm.agent-json-disable-thinking", defaultValue = "true")
    boolean llmAgentJsonDisableThinking;

    @ConfigProperty(name = "chatllm.llm.prose-disable-thinking", defaultValue = "true")
    boolean llmProseDisableThinking;

    @ConfigProperty(name = "chatllm.llm.external-model", defaultValue = "auto")
    String llmExternalModel;

    @ConfigProperty(name = "chatllm.llm.external-api-key")
    Optional<String> llmExternalApiKey;
    public String getLlmExternalApiKey() { return llmExternalApiKey != null ? llmExternalApiKey.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.binary-path", defaultValue = "/usr/local/bin/llama-server")
    String llmBinaryPath;

    @ConfigProperty(name = "chatllm.llm.model-path")
    Optional<String> llmModelPath;
    public String getLlmModelPath() { return llmModelPath != null ? llmModelPath.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.host", defaultValue = "127.0.0.1")
    String llmHost;

    @ConfigProperty(name = "chatllm.llm.port", defaultValue = "8080")
    int llmPort;

    @ConfigProperty(name = "chatllm.llm.threads", defaultValue = "6")
    int llmThreads;

    @ConfigProperty(name = "chatllm.llm.threads-http", defaultValue = "-1")
    int llmThreadsHttp;

    // ---- LLM Speculative decoding ----

    @ConfigProperty(name = "chatllm.llm.model-draft")
    Optional<String> llmModelDraft;
    public String getLlmModelDraft() { return llmModelDraft != null ? llmModelDraft.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.gpu-layers-draft", defaultValue = "-1")
    int llmGpuLayersDraft;

    @ConfigProperty(name = "chatllm.llm.draft-max", defaultValue = "16")
    int llmDraftMax;

    @ConfigProperty(name = "chatllm.llm.draft-min", defaultValue = "8")
    int llmDraftMin;

    @ConfigProperty(name = "chatllm.llm.draft-p-min", defaultValue = "0.8")
    float llmDraftPMin;

    @ConfigProperty(name = "chatllm.llm.draft-p-split", defaultValue = "0.1")
    float llmDraftPSplit;

    @ConfigProperty(name = "chatllm.llm.ctx-size-draft", defaultValue = "0")
    int llmCtxSizeDraft;

    @ConfigProperty(name = "chatllm.llm.cache-type-k-draft")
    Optional<String> llmCacheTypeKDraft;
    public String getLlmCacheTypeKDraft() { return llmCacheTypeKDraft != null ? llmCacheTypeKDraft.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.cache-type-v-draft")
    Optional<String> llmCacheTypeVDraft;
    public String getLlmCacheTypeVDraft() { return llmCacheTypeVDraft != null ? llmCacheTypeVDraft.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.device-draft")
    Optional<String> llmDeviceDraft;
    public String getLlmDeviceDraft() { return llmDeviceDraft != null ? llmDeviceDraft.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.threads-draft", defaultValue = "-1")
    int llmThreadsDraft;

    @ConfigProperty(name = "chatllm.llm.threads-batch-draft", defaultValue = "-1")
    int llmThreadsBatchDraft;

    @ConfigProperty(name = "chatllm.llm.cpu-moe-draft", defaultValue = "false")
    boolean llmCpuMoeDraft;

    @ConfigProperty(name = "chatllm.llm.n-cpu-moe-draft", defaultValue = "-1")
    int llmNCpuMoeDraft;

    // ---- LLM Context and batching ----

    @ConfigProperty(name = "chatllm.llm.context-size", defaultValue = "256000")
    int llmContextSize;

    @ConfigProperty(name = "chatllm.llm.batch-size", defaultValue = "2048")
    int llmBatchSize;

    @ConfigProperty(name = "chatllm.llm.ubatch-size", defaultValue = "512")
    int llmUbatchSize;

    @ConfigProperty(name = "chatllm.llm.parallel-slots", defaultValue = "1")
    int llmParallelSlots;

    // ---- LLM GPU and memory ----

    @ConfigProperty(name = "chatllm.llm.gpu-layers", defaultValue = "-1")
    int llmGpuLayers;

    @ConfigProperty(name = "chatllm.llm.flash-attn", defaultValue = "on")
    String llmFlashAttn;

    @ConfigProperty(name = "chatllm.llm.cache-type-k", defaultValue = "q8_0")
    String llmCacheTypeK;

    @ConfigProperty(name = "chatllm.llm.cache-type-v", defaultValue = "q4_0")
    String llmCacheTypeV;

    @ConfigProperty(name = "chatllm.llm.no-mmap", defaultValue = "false")
    boolean llmNoMmap;

    @ConfigProperty(name = "chatllm.llm.kv-offload", defaultValue = "true")
    boolean llmKvOffload;

    @ConfigProperty(name = "chatllm.llm.fit", defaultValue = "on")
    String llmFit;

    @ConfigProperty(name = "chatllm.llm.fit-target", defaultValue = "1024")
    int llmFitTarget;

    @ConfigProperty(name = "chatllm.llm.fit-ctx", defaultValue = "256000")
    int llmFitCtx;

    @ConfigProperty(name = "chatllm.llm.mlock", defaultValue = "true")
    boolean llmMlock;

    @ConfigProperty(name = "chatllm.llm.repack", defaultValue = "true")
    boolean llmRepack;

    @ConfigProperty(name = "chatllm.llm.cpu-moe", defaultValue = "false")
    boolean llmCpuMoe;

    @ConfigProperty(name = "chatllm.llm.n-cpu-moe", defaultValue = "-1")
    int llmNCpuMoe;

    @ConfigProperty(name = "chatllm.llm.op-offload", defaultValue = "true")
    boolean llmOpOffload;

    @ConfigProperty(name = "chatllm.llm.defrag-thold", defaultValue = "-1.0")
    float llmDefragThold;

    @ConfigProperty(name = "chatllm.llm.rpc")
    Optional<String> llmRpc;
    public String getLlmRpc() { return llmRpc != null ? llmRpc.orElse("") : ""; }

    // ---- LLM Multi-GPU and device selection ----

    @ConfigProperty(name = "chatllm.llm.device")
    Optional<String> llmDevice;
    public String getLlmDevice() { return llmDevice != null ? llmDevice.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.main-gpu", defaultValue = "0")
    int llmMainGpu;

    @ConfigProperty(name = "chatllm.llm.split-mode", defaultValue = "layer")
    String llmSplitMode;

    @ConfigProperty(name = "chatllm.llm.tensor-split")
    Optional<String> llmTensorSplit;
    public String getLlmTensorSplit() { return llmTensorSplit != null ? llmTensorSplit.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.numa")
    Optional<String> llmNuma;
    public String getLlmNuma() { return llmNuma != null ? llmNuma.orElse("") : ""; }

    // ---- LLM Prompt caching ----

    @ConfigProperty(name = "chatllm.llm.cache-ram", defaultValue = "512")
    int llmCacheRam;

    @ConfigProperty(name = "chatllm.llm.cache-reuse", defaultValue = "256")
    int llmCacheReuse;

    @ConfigProperty(name = "chatllm.llm.kv-unified", defaultValue = "false")
    boolean llmKvUnified;

    @ConfigProperty(name = "chatllm.llm.ctx-checkpoints", defaultValue = "8")
    int llmCtxCheckpoints;

    @ConfigProperty(name = "chatllm.llm.swa-full", defaultValue = "false")
    boolean llmSwaFull;

    @ConfigProperty(name = "chatllm.llm.cache-prompt", defaultValue = "false")
    boolean llmCachePrompt;

    // ---- LLM Sampling defaults ----

    @ConfigProperty(name = "chatllm.llm.temperature", defaultValue = "0.7")
    float llmTemperature;

    @ConfigProperty(name = "chatllm.llm.top-p", defaultValue = "0.95")
    float llmTopP;

    @ConfigProperty(name = "chatllm.llm.top-k", defaultValue = "20")
    int llmTopK;

    @ConfigProperty(name = "chatllm.llm.min-p", defaultValue = "0.0")
    float llmMinP;

    @ConfigProperty(name = "chatllm.llm.repeat-penalty", defaultValue = "1.0")
    float llmRepeatPenalty;

    @ConfigProperty(name = "chatllm.llm.max-tokens", defaultValue = "256000")
    int llmMaxTokens;

    @ConfigProperty(name = "chatllm.llm.presence-penalty", defaultValue = "0.0")
    float llmPresencePenalty;

    @ConfigProperty(name = "chatllm.llm.frequency-penalty", defaultValue = "0.0")
    float llmFrequencyPenalty;

    @ConfigProperty(name = "chatllm.llm.repeat-last-n", defaultValue = "64")
    int llmRepeatLastN;

    @ConfigProperty(name = "chatllm.llm.top-n-sigma", defaultValue = "-1.0")
    float llmTopNSigma;

    @ConfigProperty(name = "chatllm.llm.typical-p", defaultValue = "1.0")
    float llmTypicalP;

    @ConfigProperty(name = "chatllm.llm.seed", defaultValue = "-1")
    int llmSeed;

    @ConfigProperty(name = "chatllm.llm.keep", defaultValue = "-1")
    int llmKeep;

    @ConfigProperty(name = "chatllm.llm.ignore-eos", defaultValue = "false")
    boolean llmIgnoreEos;

    @ConfigProperty(name = "chatllm.llm.dynatemp-range", defaultValue = "0.0")
    float llmDynatempRange;

    @ConfigProperty(name = "chatllm.llm.dynatemp-exp", defaultValue = "1.0")
    float llmDynatempExp;

    // ---- LLM Sampling — constrained / advanced ----

    @ConfigProperty(name = "chatllm.llm.logit-bias")
    Optional<String> llmLogitBias;
    public String getLlmLogitBias() { return llmLogitBias != null ? llmLogitBias.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.grammar")
    Optional<String> llmGrammar;
    public String getLlmGrammar() { return llmGrammar != null ? llmGrammar.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.grammar-file")
    Optional<String> llmGrammarFile;
    public String getLlmGrammarFile() { return llmGrammarFile != null ? llmGrammarFile.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.json-schema")
    Optional<String> llmJsonSchema;
    public String getLlmJsonSchema() { return llmJsonSchema != null ? llmJsonSchema.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.json-schema-file")
    Optional<String> llmJsonSchemaFile;
    public String getLlmJsonSchemaFile() { return llmJsonSchemaFile != null ? llmJsonSchemaFile.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.dry-sequence-breaker")
    Optional<String> llmDrySequenceBreaker;
    public String getLlmDrySequenceBreaker() { return llmDrySequenceBreaker != null ? llmDrySequenceBreaker.orElse("") : ""; }

    // ---- LLM Mirostat sampling ----

    @ConfigProperty(name = "chatllm.llm.mirostat", defaultValue = "0")
    int llmMirostat;

    @ConfigProperty(name = "chatllm.llm.mirostat-lr", defaultValue = "0.1")
    float llmMirostatLr;

    @ConfigProperty(name = "chatllm.llm.mirostat-ent", defaultValue = "5.0")
    float llmMirostatEnt;

    // ---- LLM DRY sampling ----

    @ConfigProperty(name = "chatllm.llm.dry-multiplier", defaultValue = "0.0")
    float llmDryMultiplier;

    @ConfigProperty(name = "chatllm.llm.dry-base", defaultValue = "1.75")
    float llmDryBase;

    @ConfigProperty(name = "chatllm.llm.dry-allowed-length", defaultValue = "2")
    int llmDryAllowedLength;

    @ConfigProperty(name = "chatllm.llm.dry-penalty-last-n", defaultValue = "-1")
    int llmDryPenaltyLastN;

    // ---- LLM XTC sampling ----

    @ConfigProperty(name = "chatllm.llm.xtc-probability", defaultValue = "0.0")
    float llmXtcProbability;

    @ConfigProperty(name = "chatllm.llm.xtc-threshold", defaultValue = "0.1")
    float llmXtcThreshold;

    // ---- LLM Chat and reasoning ----

    @ConfigProperty(name = "chatllm.llm.prompt-template")
    Optional<String> llmPromptTemplate;
    public String getLlmPromptTemplate() { return llmPromptTemplate != null ? llmPromptTemplate.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.chat-template")
    Optional<String> llmChatTemplate;
    public String getLlmChatTemplate() { return llmChatTemplate != null ? llmChatTemplate.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.chat-template-kwargs")
    Optional<String> llmChatTemplateKwargs;
    public String getLlmChatTemplateKwargs() { return llmChatTemplateKwargs != null ? llmChatTemplateKwargs.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.jinja", defaultValue = "true")
    boolean llmJinja;

    @ConfigProperty(name = "chatllm.llm.reasoning-format", defaultValue = "auto")
    String llmReasoningFormat;

    @ConfigProperty(name = "chatllm.llm.reasoning-budget", defaultValue = "128")
    int llmReasoningBudget;

    @ConfigProperty(name = "chatllm.llm.gpt-oss-reasoning-effort", defaultValue = "high")
    String llmGptOssReasoningEffort;

    // ---- LLM Server features ----

    @ConfigProperty(name = "chatllm.llm.metrics", defaultValue = "true")
    boolean llmMetrics;

    @ConfigProperty(name = "chatllm.llm.webui", defaultValue = "false")
    boolean llmWebui;

    @ConfigProperty(name = "chatllm.llm.context-shift", defaultValue = "false")
    boolean llmContextShift;

    @ConfigProperty(name = "chatllm.llm.warmup", defaultValue = "true")
    boolean llmWarmup;

    @ConfigProperty(name = "chatllm.llm.cont-batching", defaultValue = "true")
    boolean llmContBatching;

    @ConfigProperty(name = "chatllm.llm.timeout", defaultValue = "600")
    int llmTimeout;

    @ConfigProperty(name = "chatllm.llm.api-key")
    Optional<String> llmApiKey;
    public String getLlmApiKey() { return llmApiKey != null ? llmApiKey.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.slots", defaultValue = "true")
    boolean llmSlots;

    @ConfigProperty(name = "chatllm.llm.props", defaultValue = "false")
    boolean llmProps;

    @ConfigProperty(name = "chatllm.llm.slot-prompt-similarity", defaultValue = "0.10")
    float llmSlotPromptSimilarity;

    @ConfigProperty(name = "chatllm.llm.api-key-file")
    Optional<String> llmApiKeyFile;
    public String getLlmApiKeyFile() { return llmApiKeyFile != null ? llmApiKeyFile.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.api-prefix")
    Optional<String> llmApiPrefix;
    public String getLlmApiPrefix() { return llmApiPrefix != null ? llmApiPrefix.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.alias")
    Optional<String> llmAlias;
    public String getLlmAlias() { return llmAlias != null ? llmAlias.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.slot-save-path")
    Optional<String> llmSlotSavePath;
    public String getLlmSlotSavePath() { return llmSlotSavePath != null ? llmSlotSavePath.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.reuse-port", defaultValue = "false")
    boolean llmReusePort;

    @ConfigProperty(name = "chatllm.llm.no-host", defaultValue = "false")
    boolean llmNoHost;

    @ConfigProperty(name = "chatllm.llm.offline", defaultValue = "false")
    boolean llmOffline;

    @ConfigProperty(name = "chatllm.llm.prefill-assistant", defaultValue = "true")
    boolean llmPrefillAssistant;

    @ConfigProperty(name = "chatllm.llm.chat-template-file")
    Optional<String> llmChatTemplateFile;
    public String getLlmChatTemplateFile() { return llmChatTemplateFile != null ? llmChatTemplateFile.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.ssl-cert-file")
    Optional<String> llmSslCertFile;
    public String getLlmSslCertFile() { return llmSslCertFile != null ? llmSslCertFile.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.ssl-key-file")
    Optional<String> llmSslKeyFile;
    public String getLlmSslKeyFile() { return llmSslKeyFile != null ? llmSslKeyFile.orElse("") : ""; }

    // ---- LLM Multi-modal (vision) ----

    @ConfigProperty(name = "chatllm.llm.mmproj")
    Optional<String> llmMmproj;
    public String getLlmMmproj() { return llmMmproj != null ? llmMmproj.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.mmproj-url")
    Optional<String> llmMmprojUrl;
    public String getLlmMmprojUrl() { return llmMmprojUrl != null ? llmMmprojUrl.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.mmproj-auto", defaultValue = "true")
    boolean llmMmprojAuto;

    @ConfigProperty(name = "chatllm.llm.mmproj-offload", defaultValue = "true")
    boolean llmMmprojOffload;

    // ---- LLM CPU Affinity & Scheduling ----

    @ConfigProperty(name = "chatllm.llm.threads-batch", defaultValue = "-1")
    int llmThreadsBatch;

    @ConfigProperty(name = "chatllm.llm.cpu-mask")
    Optional<String> llmCpuMask;
    public String getLlmCpuMask() { return llmCpuMask != null ? llmCpuMask.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.cpu-range")
    Optional<String> llmCpuRange;
    public String getLlmCpuRange() { return llmCpuRange != null ? llmCpuRange.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.cpu-strict", defaultValue = "0")
    int llmCpuStrict;

    @ConfigProperty(name = "chatllm.llm.prio", defaultValue = "0")
    int llmPrio;

    @ConfigProperty(name = "chatllm.llm.poll", defaultValue = "50")
    int llmPoll;

    @ConfigProperty(name = "chatllm.llm.cpu-mask-batch")
    Optional<String> llmCpuMaskBatch;
    public String getLlmCpuMaskBatch() { return llmCpuMaskBatch != null ? llmCpuMaskBatch.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.cpu-range-batch")
    Optional<String> llmCpuRangeBatch;
    public String getLlmCpuRangeBatch() { return llmCpuRangeBatch != null ? llmCpuRangeBatch.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.cpu-strict-batch", defaultValue = "-1")
    int llmCpuStrictBatch;

    @ConfigProperty(name = "chatllm.llm.prio-batch", defaultValue = "-1")
    int llmPrioBatch;

    @ConfigProperty(name = "chatllm.llm.poll-batch", defaultValue = "-1")
    int llmPollBatch;

    // ---- LLM LoRA adapters ----

    @ConfigProperty(name = "chatllm.llm.lora-path")
    Optional<String> llmLoraPath;
    public String getLlmLoraPath() { return llmLoraPath != null ? llmLoraPath.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.lora-scaled")
    Optional<String> llmLoraScaled;
    public String getLlmLoraScaled() { return llmLoraScaled != null ? llmLoraScaled.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.lora-init-without-apply", defaultValue = "false")
    boolean llmLoraInitWithoutApply;

    // ---- LLM RoPE scaling ----

    @ConfigProperty(name = "chatllm.llm.rope-scaling")
    Optional<String> llmRopeScaling;
    public String getLlmRopeScaling() { return llmRopeScaling != null ? llmRopeScaling.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.llm.rope-scale", defaultValue = "0.0")
    float llmRopeScale;

    @ConfigProperty(name = "chatllm.llm.rope-freq-base", defaultValue = "0.0")
    float llmRopeFreqBase;

    @ConfigProperty(name = "chatllm.llm.rope-freq-scale", defaultValue = "0.0")
    float llmRopeFreqScale;

    @ConfigProperty(name = "chatllm.llm.yarn-orig-ctx", defaultValue = "0")
    int llmYarnOrigCtx;

    @ConfigProperty(name = "chatllm.llm.yarn-ext-factor", defaultValue = "-1.0")
    float llmYarnExtFactor;

    @ConfigProperty(name = "chatllm.llm.yarn-attn-factor", defaultValue = "-1.0")
    float llmYarnAttnFactor;

    @ConfigProperty(name = "chatllm.llm.yarn-beta-slow", defaultValue = "-1.0")
    float llmYarnBetaSlow;

    @ConfigProperty(name = "chatllm.llm.yarn-beta-fast", defaultValue = "-1.0")
    float llmYarnBetaFast;

    // ---- LLM Connection pool ----

    @ConfigProperty(name = "chatllm.llm.max-connections", defaultValue = "4")
    int llmMaxConnections;

    @ConfigProperty(name = "chatllm.llm.max-queue-size", defaultValue = "100")
    int llmMaxQueueSize;

    @ConfigProperty(name = "chatllm.llm.request-timeout", defaultValue = "1800000")
    int llmRequestTimeout;

    // Idle timeout: fail a generation only if NO traffic has been received from
    // llama-server for this duration. Each streamed token resets the counter,
    // so long generations are not capped by absolute elapsed time.
    @ConfigProperty(name = "chatllm.llm.idle-timeout", defaultValue = "120000")
    int llmIdleTimeout;

    @ConfigProperty(name = "chatllm.llm.retry-attempts", defaultValue = "2")
    int llmRetryAttempts;

    @ConfigProperty(name = "chatllm.llm.retry-delay", defaultValue = "1000")
    int llmRetryDelay;

    @ConfigProperty(name = "chatllm.llm.conversation-history-limit", defaultValue = "10")
    int llmConversationHistoryLimit;

    @ConfigProperty(name = "chatllm.llm.context-custom")
    Optional<String> llmContextCustom;
    public String getLlmContextCustom() { return llmContextCustom != null ? llmContextCustom.orElse("") : ""; }

    // ---- LLM Paths ----

    @ConfigProperty(name = "chatllm.llm.gpu-memory-mb", defaultValue = "0")
    long gpuMemoryMB;

    @ConfigProperty(name = "chatllm.llm.models-directory", defaultValue = "/models")
    String modelsDirectory;

    // ---- Security ----

    @ConfigProperty(name = "chatllm.allowed-origins", defaultValue = "*")
    String allowedOrigins;

    @ConfigProperty(name = "chatllm.rate-limit-per-minute", defaultValue = "600")
    int rateLimitPerMinute;

    @ConfigProperty(name = "chatllm.message-rate-limit-per-minute", defaultValue = "10")
    int messageRateLimitPerMinute;

    @ConfigProperty(name = "chatllm.login-rate-limit-per-minute", defaultValue = "50")
    int loginRateLimitPerMinute;

    @ConfigProperty(name = "chatllm.bcrypt-rounds", defaultValue = "12")
    int bcryptRounds;

    // ---- Debug / auth bypass ----

    @ConfigProperty(name = "chatllm.auth-bypass-enabled", defaultValue = "false")
    boolean authBypassEnabled;

    // ---- Feature flags ----

    @ConfigProperty(name = "chatllm.features.metrics-enabled", defaultValue = "true")
    boolean enableMetrics;

    @ConfigProperty(name = "chatllm.features.health-check-enabled", defaultValue = "true")
    boolean enableHealthCheck;

    @ConfigProperty(name = "chatllm.features.graphql-playground-enabled", defaultValue = "false")
    boolean enableGraphQLPlayground;

    @ConfigProperty(name = "chatllm.features.clustering-enabled", defaultValue = "false")
    boolean enableClustering;

    // ---- Cache ----

    @ConfigProperty(name = "chatllm.cache.settings-ttl", defaultValue = "3600")
    int cacheSettings;

    // ---- Agent Pipeline ----

    @ConfigProperty(name = "chatllm.agent.default-model")
    Optional<String> agentDefaultModel;
    public String getAgentDefaultModel() { return agentDefaultModel != null ? agentDefaultModel.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.agent.highlighter-model")
    Optional<String> agentHighlighterModel;
    public String getAgentHighlighterModel() { return agentHighlighterModel != null ? agentHighlighterModel.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.agent.muse-model")
    Optional<String> agentMuseModel;
    public String getAgentMuseModel() { return agentMuseModel != null ? agentMuseModel.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.agent.narrator-model")
    Optional<String> agentNarratorModel;
    public String getAgentNarratorModel() { return agentNarratorModel != null ? agentNarratorModel.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.agent.accountant-model")
    Optional<String> agentAccountantModel;
    public String getAgentAccountantModel() { return agentAccountantModel != null ? agentAccountantModel.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.agent.skeptic-model")
    Optional<String> agentSkepticModel;
    public String getAgentSkepticModel() { return agentSkepticModel != null ? agentSkepticModel.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.agent.skeptic-max-retries", defaultValue = "2")
    int agentSkepticMaxRetries;

    @ConfigProperty(name = "chatllm.agent.skeptic-narration-gate-enabled", defaultValue = "true")
    boolean agentSkepticNarrationGateEnabled;

    @ConfigProperty(name = "chatllm.agent.skeptic-character-gate-enabled", defaultValue = "true")
    boolean agentSkepticCharacterGateEnabled;

    @ConfigProperty(name = "chatllm.agent.caster-model")
    Optional<String> agentCasterModel;
    public String getAgentCasterModel() { return agentCasterModel != null ? agentCasterModel.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.agent.casting-enabled", defaultValue = "true")
    boolean agentCastingEnabled;

    // ---- RAG ----

    @ConfigProperty(name = "chatllm.rag.promotion-threshold", defaultValue = "0.7")
    float ragPromotionThreshold;

    @ConfigProperty(name = "chatllm.rag.max-retrieved-entries", defaultValue = "15")
    int ragMaxRetrievedEntries;

    @ConfigProperty(name = "chatllm.rag.max-context-tokens", defaultValue = "1500")
    int ragMaxContextTokens;

    @ConfigProperty(name = "chatllm.rag.recency-decay-rate", defaultValue = "0.02")
    float ragRecencyDecayRate;

    @ConfigProperty(name = "chatllm.rag.tag-match-weight", defaultValue = "0.35")
    float ragTagMatchWeight;

    @ConfigProperty(name = "chatllm.rag.fulltext-weight", defaultValue = "0.30")
    float ragFulltextWeight;

    @ConfigProperty(name = "chatllm.rag.importance-weight", defaultValue = "0.20")
    float ragImportanceWeight;

    @ConfigProperty(name = "chatllm.rag.recency-weight", defaultValue = "0.15")
    float ragRecencyWeight;

    @ConfigProperty(name = "chatllm.rag.enabled", defaultValue = "true")
    boolean ragEnabled;

    @ConfigProperty(name = "chatllm.rag.promotion-enabled", defaultValue = "true")
    boolean ragPromotionEnabled;

    // ---- ComfyUI ----

    @ConfigProperty(name = "chatllm.comfyui.host", defaultValue = "localhost")
    String comfyuiHost;

    @ConfigProperty(name = "chatllm.comfyui.port", defaultValue = "8188")
    int comfyuiPort;

    @ConfigProperty(name = "chatllm.comfyui.enabled", defaultValue = "false")
    boolean comfyuiEnabled;

    @ConfigProperty(name = "chatllm.comfyui.output-dir", defaultValue = "./comfyui-output")
    String comfyuiOutputDir;

    @ConfigProperty(name = "chatllm.comfyui.max-queue-size", defaultValue = "5")
    int comfyuiMaxQueueSize;

    @ConfigProperty(name = "chatllm.comfyui.default-steps", defaultValue = "20")
    int comfyuiDefaultSteps;

    @ConfigProperty(name = "chatllm.comfyui.default-checkpoint")
    Optional<String> comfyuiDefaultCheckpoint;
    public String getComfyuiDefaultCheckpoint() { return comfyuiDefaultCheckpoint != null ? comfyuiDefaultCheckpoint.orElse("") : ""; }

    @ConfigProperty(name = "chatllm.comfyui.default-sampler", defaultValue = "euler_a")
    String comfyuiDefaultSampler;

    @ConfigProperty(name = "chatllm.comfyui.default-lora")
    Optional<String> comfyuiDefaultLora;
    public String getComfyuiDefaultLora() { return comfyuiDefaultLora != null ? comfyuiDefaultLora.orElse("") : ""; }

    // ---- Computed methods (not config properties) ----

    /**
     * Build the Redis connection string from Quarkus-managed redis config.
     * Kept as a convenience for code that still builds the URI manually.
     */
    public String getRedisConnectionString() {
        // Quarkus already exposes the full URI in quarkus.redis.hosts,
        // so just return it directly.
        return redisHosts;
    }

    /**
     * Parse max body size from the Quarkus-format string (e.g. "10M") to bytes.
     */
    public long getMaxBodySize() {
        if (maxBodySizeStr == null || maxBodySizeStr.isEmpty()) {
            return 10_485_760L; // 10MB default
        }
        String s = maxBodySizeStr.trim().toUpperCase();
        if (s.endsWith("M")) {
            return Long.parseLong(s.substring(0, s.length() - 1)) * 1_048_576L;
        } else if (s.endsWith("K")) {
            return Long.parseLong(s.substring(0, s.length() - 1)) * 1_024L;
        } else if (s.endsWith("G")) {
            return Long.parseLong(s.substring(0, s.length() - 1)) * 1_073_741_824L;
        }
        return Long.parseLong(s);
    }

    public Integer getRateLimitMaxRequests() {
        return rateLimitPerMinute;
    }

    public Integer getMessageRateLimitMaxRequests() {
        return messageRateLimitPerMinute;
    }

    public Integer getLoginRateLimitMaxRequests() {
        return loginRateLimitPerMinute;
    }
}
