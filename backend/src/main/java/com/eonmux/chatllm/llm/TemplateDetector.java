package com.eonmux.chatllm.llm;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.ext.web.client.WebClient;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Enhanced template detector for LLM models.
 * Supports 50+ chat templates with detection from server API, GGUF metadata, and filename patterns.
 * Updated for llama.cpp b5000+ built-in templates.
 */
@Slf4j
public class TemplateDetector {

    // Built-in templates from llama-server (as of b5000+)
    // These match the --chat-template option values
    public static final String BAILING = "bailing";
    public static final String BAILING_THINK = "bailing-think";
    public static final String BAILING2 = "bailing2";
    public static final String CHATGLM3 = "chatglm3";
    public static final String CHATGLM4 = "chatglm4";
    public static final String CHATML = "chatml";
    public static final String COMMAND_R = "command-r";
    public static final String DEEPSEEK = "deepseek";
    public static final String DEEPSEEK2 = "deepseek2";
    public static final String DEEPSEEK3 = "deepseek3";
    public static final String EXAONE3 = "exaone3";
    public static final String EXAONE4 = "exaone4";
    public static final String FALCON3 = "falcon3";
    public static final String GEMMA = "gemma";
    public static final String GIGACHAT = "gigachat";
    public static final String GLMEDGE = "glmedge";
    public static final String GPT_OSS = "gpt-oss";
    public static final String GRANITE = "granite";
    public static final String GROK2 = "grok-2";
    public static final String HUNYUAN_DENSE = "hunyuan-dense";
    public static final String HUNYUAN_MOE = "hunyuan-moe";
    public static final String KIMI_K2 = "kimi-k2";
    public static final String LLAMA2 = "llama2";
    public static final String LLAMA2_SYS = "llama2-sys";
    public static final String LLAMA2_SYS_BOS = "llama2-sys-bos";
    public static final String LLAMA2_SYS_STRIP = "llama2-sys-strip";
    public static final String LLAMA3 = "llama3";
    public static final String LLAMA4 = "llama4";
    public static final String MEGREZ = "megrez";
    public static final String MINICPM = "minicpm";
    public static final String MISTRAL_V1 = "mistral-v1";
    public static final String MISTRAL_V3 = "mistral-v3";
    public static final String MISTRAL_V3_TEKKEN = "mistral-v3-tekken";
    public static final String MISTRAL_V7 = "mistral-v7";
    public static final String MISTRAL_V7_TEKKEN = "mistral-v7-tekken";
    public static final String MONARCH = "monarch";
    public static final String OPENCHAT = "openchat";
    public static final String ORION = "orion";
    public static final String PANGU_EMBEDDED = "pangu-embedded";
    public static final String PHI3 = "phi3";
    public static final String PHI4 = "phi4";
    public static final String RWKV_WORLD = "rwkv-world";
    public static final String SEED_OSS = "seed_oss";
    public static final String SMOLVLM = "smolvlm";
    public static final String VICUNA = "vicuna";
    public static final String VICUNA_ORCA = "vicuna-orca";
    public static final String YANDEX = "yandex";
    public static final String ZEPHYR = "zephyr";

    // Legacy/custom template names (for backward compatibility)
    public static final String ALPACA = "alpaca";
    public static final String QWEN = "qwen";
    public static final String NEURAL = "neural";
    public static final String SOLAR = "solar";
    public static final String ORCA = "orca";
    public static final String STABLELM = "stablelm";
    public static final String YI = "yi";
    public static final String NOUS = "nous";
    public static final String WIZARDLM = "wizardlm";
    
    /**
     * Detect template from multiple sources.
     */
    public static Uni<String> detect(WebClient webClient, String modelPath) {
        // Try server API first
        return detectFromServer(webClient)
            .onFailure().recoverWithItem(error1 -> {
                // Try filename detection as fallback
                String fromFilename = detectFromFilename(modelPath);
                if (fromFilename != null) {
                    return fromFilename;
                }
                // Default to CHATML
                log.warn("Could not detect template, using default CHATML");
                return "CHATML";
            });
    }
    
    /**
     * Detect template from llama server API.
     */
    private static Uni<String> detectFromServer(WebClient webClient) {
        // Try /props endpoint
        return webClient.get("/props")
            .send()
            .chain(response -> {
                if (response.statusCode() == 200) {
                    try {
                        JsonObject props = response.bodyAsJsonObject();

                        // Log model info for debugging template detection
                        if (props.containsKey("default_generation_settings")) {
                            JsonObject settings = props.getJsonObject("default_generation_settings");
                            if (settings != null && settings.containsKey("model")) {
                                log.debug("Model path from props: {}", settings.getString("model"));
                            }
                        }

                        String template = extractTemplateFromProps(props);
                        if (template != null) {
                            log.info("Detected template: {}", template);
                            return Uni.createFrom().item(template);
                        } else {
                            return Uni.createFrom().failure(new RuntimeException("No template in props"));
                        }
                    } catch (Exception e) {
                        return Uni.createFrom().failure(e);
                    }
                } else {
                    return Uni.createFrom().failure(new RuntimeException("Props endpoint failed"));
                }
            });
    }
    
    /**
     * Extract template from server props.
     * Priority: chat_template content (if clearly identifiable) > model name/path detection
     *
     * IMPORTANT: We check the actual chat_template content FIRST for formats that are
     * unambiguously identifiable (like ChatML with <|im_start|>). This prevents misdetection
     * when models have names like "gpt-oss" but actually use a different template format
     * (e.g., derestricted models often use ChatML but keep "gpt-oss" in their filename).
     */
    private static String extractTemplateFromProps(JsonObject props) {
        // Get model path for logging and fallback detection
        String modelPath = null;

        if (props.containsKey("default_generation_settings")) {
            JsonObject settings = props.getJsonObject("default_generation_settings");
            if (settings != null && settings.containsKey("model")) {
                modelPath = settings.getString("model");
            }
        }

        // Also check for model_path field directly
        if (modelPath == null && props.containsKey("model_path")) {
            modelPath = props.getString("model_path");
        }

        // Also check model.name if available
        if (modelPath == null && props.containsKey("model") && props.getJsonObject("model") != null) {
            JsonObject model = props.getJsonObject("model");
            if (model.containsKey("name")) {
                modelPath = model.getString("name");
            }
            if (modelPath == null && model.containsKey("path")) {
                modelPath = model.getString("path");
            }
        }

        // FIRST: Check chat_template content for clearly identifiable formats
        // This takes priority because the embedded template reflects what the model
        // was actually trained with, regardless of what the filename suggests
        String chatTemplateContent = null;
        if (props.containsKey("chat_template")) {
            chatTemplateContent = props.getString("chat_template");
        } else if (props.containsKey("model") && props.getJsonObject("model") != null) {
            JsonObject model = props.getJsonObject("model");
            if (model.containsKey("chat_template")) {
                chatTemplateContent = model.getString("chat_template");
            }
        }

        if (chatTemplateContent != null && !chatTemplateContent.isEmpty()) {
            // Check if this is raw jinja template content
            if (chatTemplateContent.contains("{%") || chatTemplateContent.contains("{{")) {
                String detected = detectFromChatTemplateString(chatTemplateContent);
                if (detected != null) {
                    // Only use detected template if it's a "high confidence" format
                    // (formats with distinctive tokens that are unlikely to be misdetected)
                    if (isHighConfidenceFormat(detected, chatTemplateContent)) {
                        log.info("Detected {} template from chat_template content (high confidence)", detected);
                        return detected;
                    }
                }
            } else {
                // It's a template name, normalize it
                String normalized = normalizeTemplate(chatTemplateContent);
                if (normalized != null) {
                    log.info("Using specified template name from chat_template: {}", normalized);
                    return normalized;
                }
            }
        }

        // SECOND: Fall back to model name detection
        // This handles cases where chat_template isn't embedded or can't be identified
        if (modelPath != null) {
            String detectedFromName = detectFromModelName(modelPath);
            if (detectedFromName != null) {
                log.info("Detected {} template from model name: {}", detectedFromName, modelPath);
                return detectedFromName;
            }
        }

        // THIRD: Use the chat_template even if low confidence (better than nothing)
        if (chatTemplateContent != null && !chatTemplateContent.isEmpty()) {
            String detected = detectOrNormalizeTemplate(chatTemplateContent);
            if (detected != null) {
                log.info("Using detected template from chat_template (low confidence): {}", detected);
                return detected;
            }
        }

        return null;
    }

    /**
     * Check if a detected template format is "high confidence" - meaning the template
     * content contains distinctive tokens that make misdetection unlikely.
     */
    private static boolean isHighConfidenceFormat(String detected, String templateContent) {
        if (detected == null || templateContent == null) return false;

        String lower = templateContent.toLowerCase();

        switch (detected) {
            case CHATML:
                // ChatML has very distinctive <|im_start|> and <|im_end|> tokens
                return lower.contains("<|im_start|>") && lower.contains("<|im_end|>");

            case LLAMA3:
                // Llama3 has distinctive header tokens
                return lower.contains("<|start_header_id|>") && lower.contains("<|end_header_id|>");

            case LLAMA2:
                // Llama2 has distinctive [INST] format (but not mistral/ministral)
                return lower.contains("[inst]") && lower.contains("[/inst]") &&
                       !lower.contains("mistral") && !lower.contains("ministral");

            case MISTRAL_V1:
            case MISTRAL_V3:
            case MISTRAL_V3_TEKKEN:
            case MISTRAL_V7:
            case MISTRAL_V7_TEKKEN:
                // Mistral family uses [INST] format with mistral/ministral in system prompt
                return lower.contains("[inst]") && lower.contains("[/inst]") &&
                       (lower.contains("mistral") || lower.contains("ministral"));

            case GEMMA:
                // Gemma has distinctive turn tokens
                return lower.contains("<start_of_turn>") && lower.contains("<end_of_turn>");

            case DEEPSEEK3:
            case DEEPSEEK2:
                // DeepSeek uses fullwidth unicode tokens
                return templateContent.contains("<｜User｜>") || templateContent.contains("<｜user｜>") ||
                       templateContent.contains("<｜Assistant｜>") || templateContent.contains("<｜assistant｜>");

            case GPT_OSS:
                // GPT-OSS uses <|start|>role<|message|> format with <|channel|>
                return lower.contains("<|start|>") && lower.contains("<|message|>");

            case PHI3:
            case PHI4:
                // Phi uses <|user|> but also has "phi" in template content
                return lower.contains("<|user|>") && lower.contains("<|assistant|>") && lower.contains("phi");

            case ZEPHYR:
                // Zephyr uses generic <|user|>/<|assistant|> - less distinctive
                return lower.contains("<|user|>") && lower.contains("<|assistant|>") &&
                       !lower.contains("phi"); // Make sure it's not Phi

            default:
                // For other formats, don't consider them high confidence
                return false;
        }
    }

    /**
     * Detect template from a string that could be either a template name or raw jinja content.
     */
    private static String detectOrNormalizeTemplate(String template) {
        if (template == null || template.isEmpty()) {
            return null;
        }

        // Check if this is a raw jinja template (contains template syntax)
        if (template.contains("{%") || template.contains("{{")) {
            // This is a raw jinja template, analyze its content
            String detected = detectFromChatTemplateString(template);
            if (detected != null) {
                return detected;
            }
            // If we couldn't detect the format, let llama-server use its embedded template
            return null;
        }

        // This is a template name, normalize it
        return normalizeTemplate(template);
    }
    
    /**
     * Detect template from filename.
     * Returns built-in template names that match llama-server's --chat-template option.
     */
    private static String detectFromFilename(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            return null;
        }

        Path path = Paths.get(modelPath);
        String filename = path.getFileName().toString().toLowerCase();

        // Llama family
        if (filename.contains("llama-4") || filename.contains("llama4")) {
            return LLAMA4;
        }
        if (filename.contains("llama-3") || filename.contains("llama3") || filename.contains("meta-llama-3")) {
            return LLAMA3;
        }
        if (filename.contains("llama-2") || filename.contains("llama2") || filename.contains("meta-llama-2")) {
            return LLAMA2;
        }

        // Mistral family (use appropriate version)
        // Note: "ministral" is Mistral's smaller model line, uses same template format
        if (filename.contains("mistral") || filename.contains("mixtral") || filename.contains("ministral")) {
            if (filename.contains("tekken")) {
                return MISTRAL_V7_TEKKEN;
            }
            if (filename.contains("v3") || filename.contains("-3-")) {
                return MISTRAL_V3;
            }
            return MISTRAL_V7; // Latest default
        }

        // DeepSeek family
        if (filename.contains("deepseek")) {
            if (filename.contains("v3") || filename.contains("-3")) {
                return DEEPSEEK3;
            }
            if (filename.contains("v2") || filename.contains("-2")) {
                return DEEPSEEK2;
            }
            return DEEPSEEK;
        }

        // Phi family
        if (filename.contains("phi-4") || filename.contains("phi4")) {
            return PHI4;
        }
        if (filename.contains("phi-3") || filename.contains("phi3") || filename.contains("microsoft-phi")) {
            return PHI3;
        }

        // ChatGLM family
        if (filename.contains("chatglm") || filename.contains("chat-glm") || filename.contains("glm")) {
            if (filename.contains("4") || filename.contains("glm4")) {
                return CHATGLM4;
            }
            return CHATGLM3;
        }

        // Other built-in templates
        if (filename.contains("chatml") || filename.contains("chat-ml") ||
            filename.contains("dolphin") || filename.contains("openhermes")) {
            return CHATML;
        }
        if (filename.contains("gemma")) {
            return GEMMA;
        }
        if (filename.contains("command-r") || filename.contains("commandr") || filename.contains("cohere")) {
            return COMMAND_R;
        }
        if (filename.contains("vicuna")) {
            if (filename.contains("orca")) {
                return VICUNA_ORCA;
            }
            return VICUNA;
        }
        if (filename.contains("zephyr")) {
            return ZEPHYR;
        }
        if (filename.contains("openchat")) {
            return OPENCHAT;
        }
        if (filename.contains("falcon") && filename.contains("3")) {
            return FALCON3;
        }
        if (filename.contains("granite")) {
            return GRANITE;
        }
        if (filename.contains("grok")) {
            return GROK2;
        }
        if (filename.contains("exaone")) {
            if (filename.contains("4")) {
                return EXAONE4;
            }
            return EXAONE3;
        }
        if (filename.contains("minicpm")) {
            return MINICPM;
        }
        if (filename.contains("monarch")) {
            return MONARCH;
        }
        if (filename.contains("orion")) {
            return ORION;
        }
        if (filename.contains("rwkv")) {
            return RWKV_WORLD;
        }
        if (filename.contains("smolvlm") || filename.contains("smol-vlm")) {
            return SMOLVLM;
        }
        if (filename.contains("hunyuan")) {
            if (filename.contains("moe")) {
                return HUNYUAN_MOE;
            }
            return HUNYUAN_DENSE;
        }
        if (filename.contains("kimi") || filename.contains("moonshot")) {
            return KIMI_K2;
        }
        if (filename.contains("bailing")) {
            if (filename.contains("think")) {
                return BAILING_THINK;
            }
            if (filename.contains("2")) {
                return BAILING2;
            }
            return BAILING;
        }
        if (filename.contains("megrez")) {
            return MEGREZ;
        }
        if (filename.contains("gigachat")) {
            return GIGACHAT;
        }
        if (filename.contains("yandex")) {
            return YANDEX;
        }
        if (filename.contains("gpt-oss") || filename.contains("gptoss") || filename.contains("gpt_oss")) {
            return GPT_OSS;
        }

        // Legacy/custom templates (not built-in but still useful for detection)
        if (filename.contains("qwen")) {
            return QWEN;
        }
        if (filename.contains("alpaca")) {
            return ALPACA;
        }
        if (filename.contains("wizardlm") || filename.contains("wizard-lm") || filename.contains("wizardcoder")) {
            return WIZARDLM;
        }
        if (filename.contains("neural-chat") || filename.contains("neuralchat")) {
            return NEURAL;
        }
        if (filename.contains("solar")) {
            return SOLAR;
        }
        if (filename.contains("orca") && !filename.contains("vicuna")) {
            return ORCA;
        }
        if (filename.contains("stablelm") || filename.contains("stable-lm")) {
            return STABLELM;
        }
        if (filename.contains("yi-") || filename.contains("-yi-")) {
            return YI;
        }
        if (filename.contains("nous-hermes") || filename.contains("noushermes")) {
            return NOUS;
        }

        // Generic fallback patterns
        if (filename.contains("instruct")) {
            return CHATML; // Most instruct models use ChatML-like format
        }
        if (filename.contains("chat")) {
            return CHATML;
        }

        // Return null to let llama-server use model's embedded template
        return null;
    }
    
    /**
     * Detect template from model name.
     */
    private static String detectFromModelName(String modelName) {
        if (modelName == null) {
            return null;
        }
        
        // Use filename detection logic for model names
        return detectFromFilename(modelName);
    }
    
    /**
     * Get all available templates.
     * Returns all built-in llama-server templates (b5000+) plus commonly used custom templates.
     */
    public static JsonArray getAvailableTemplates() {
        JsonArray templates = new JsonArray();

        // Built-in llama-server templates (alphabetical)
        templates.add(createTemplateInfo(BAILING, "Bailing - Baichuan format"));
        templates.add(createTemplateInfo(BAILING_THINK, "Bailing Think - reasoning format"));
        templates.add(createTemplateInfo(BAILING2, "Bailing 2 format"));
        templates.add(createTemplateInfo(CHATGLM3, "ChatGLM 3 format"));
        templates.add(createTemplateInfo(CHATGLM4, "ChatGLM 4 format"));
        templates.add(createTemplateInfo(CHATML, "ChatML - <|im_start|> format"));
        templates.add(createTemplateInfo(COMMAND_R, "Cohere Command-R format"));
        templates.add(createTemplateInfo(DEEPSEEK, "DeepSeek format"));
        templates.add(createTemplateInfo(DEEPSEEK2, "DeepSeek V2 format"));
        templates.add(createTemplateInfo(DEEPSEEK3, "DeepSeek V3 format"));
        templates.add(createTemplateInfo(EXAONE3, "EXAONE 3 format"));
        templates.add(createTemplateInfo(EXAONE4, "EXAONE 4 format"));
        templates.add(createTemplateInfo(FALCON3, "Falcon 3 format"));
        templates.add(createTemplateInfo(GEMMA, "Google Gemma format"));
        templates.add(createTemplateInfo(GIGACHAT, "GigaChat format"));
        templates.add(createTemplateInfo(GLMEDGE, "GLM Edge format"));
        templates.add(createTemplateInfo(GPT_OSS, "GPT OSS format"));
        templates.add(createTemplateInfo(GRANITE, "IBM Granite format"));
        templates.add(createTemplateInfo(GROK2, "Grok 2 format"));
        templates.add(createTemplateInfo(HUNYUAN_DENSE, "Hunyuan Dense format"));
        templates.add(createTemplateInfo(HUNYUAN_MOE, "Hunyuan MoE format"));
        templates.add(createTemplateInfo(KIMI_K2, "Kimi K2 / Moonshot format"));
        templates.add(createTemplateInfo(LLAMA2, "Llama 2 Chat - [INST] format"));
        templates.add(createTemplateInfo(LLAMA2_SYS, "Llama 2 with system prompt"));
        templates.add(createTemplateInfo(LLAMA2_SYS_BOS, "Llama 2 with BOS token"));
        templates.add(createTemplateInfo(LLAMA2_SYS_STRIP, "Llama 2 stripped system"));
        templates.add(createTemplateInfo(LLAMA3, "Llama 3 - <|start_header_id|> format"));
        templates.add(createTemplateInfo(LLAMA4, "Llama 4 format"));
        templates.add(createTemplateInfo(MEGREZ, "Megrez format"));
        templates.add(createTemplateInfo(MINICPM, "MiniCPM format"));
        templates.add(createTemplateInfo(MISTRAL_V1, "Mistral V1 format"));
        templates.add(createTemplateInfo(MISTRAL_V3, "Mistral V3 format"));
        templates.add(createTemplateInfo(MISTRAL_V3_TEKKEN, "Mistral V3 Tekken format"));
        templates.add(createTemplateInfo(MISTRAL_V7, "Mistral V7 format"));
        templates.add(createTemplateInfo(MISTRAL_V7_TEKKEN, "Mistral V7 Tekken format"));
        templates.add(createTemplateInfo(MONARCH, "Monarch format"));
        templates.add(createTemplateInfo(OPENCHAT, "OpenChat - GPT4 Correct format"));
        templates.add(createTemplateInfo(ORION, "Orion format"));
        templates.add(createTemplateInfo(PANGU_EMBEDDED, "PanGu Embedded format"));
        templates.add(createTemplateInfo(PHI3, "Microsoft Phi-3 format"));
        templates.add(createTemplateInfo(PHI4, "Microsoft Phi-4 format"));
        templates.add(createTemplateInfo(RWKV_WORLD, "RWKV World format"));
        templates.add(createTemplateInfo(SEED_OSS, "Seed OSS format"));
        templates.add(createTemplateInfo(SMOLVLM, "SmolVLM format"));
        templates.add(createTemplateInfo(VICUNA, "Vicuna - USER:/ASSISTANT: format"));
        templates.add(createTemplateInfo(VICUNA_ORCA, "Vicuna Orca format"));
        templates.add(createTemplateInfo(YANDEX, "Yandex format"));
        templates.add(createTemplateInfo(ZEPHYR, "Zephyr - <|user|>/<|assistant|> format"));

        // Legacy/custom templates (not built-in but commonly used)
        templates.add(createTemplateInfo(ALPACA, "Alpaca - ### Instruction format"));
        templates.add(createTemplateInfo(NEURAL, "Intel Neural Chat format"));
        templates.add(createTemplateInfo(NOUS, "Nous Hermes format"));
        templates.add(createTemplateInfo(ORCA, "Orca format"));
        templates.add(createTemplateInfo(QWEN, "Qwen Chat format"));
        templates.add(createTemplateInfo(SOLAR, "SOLAR format"));
        templates.add(createTemplateInfo(STABLELM, "StableLM format"));
        templates.add(createTemplateInfo(WIZARDLM, "WizardLM/WizardCoder format"));
        templates.add(createTemplateInfo(YI, "Yi Chat format"));

        // Auto-detect option
        templates.add(createTemplateInfo("AUTO", "Auto-detect from model"));

        return templates;
    }
    
    /**
     * Create template info object.
     */
    private static JsonObject createTemplateInfo(String name, String description) {
        return new JsonObject()
            .put("name", name)
            .put("description", description);
    }
    
    /**
     * Normalize template name to match llama-server's built-in template names.
     * Templates are lowercase with hyphens (e.g., "mistral-v7", "deepseek3").
     */
    private static String normalizeTemplate(String template) {
        if (template == null) {
            return null;
        }

        // Normalize: lowercase, replace underscores with hyphens
        String normalized = template.toLowerCase()
            .replace("_", "-")
            .replace(" ", "-")
            .trim();

        // Map common variations to standard llama-server template names
        switch (normalized) {
            // ChatML variations
            case "chat-ml":
            case "chatml":
                return CHATML;

            // Llama family
            case "llama-4":
            case "llama4":
                return LLAMA4;
            case "llama-3":
            case "llama3":
            case "meta-llama-3":
                return LLAMA3;
            case "llama-2":
            case "llama2":
            case "llama-v2":
                return LLAMA2;
            case "llama2-sys":
            case "llama-2-sys":
                return LLAMA2_SYS;

            // Mistral family
            case "mistral":
            case "mixtral":
            case "mistral-v7":
                return MISTRAL_V7;
            case "mistral-v3":
                return MISTRAL_V3;
            case "mistral-v1":
                return MISTRAL_V1;

            // DeepSeek family
            case "deep-seek":
            case "deepseek":
                return DEEPSEEK;
            case "deepseek-2":
            case "deepseek2":
                return DEEPSEEK2;
            case "deepseek-3":
            case "deepseek3":
                return DEEPSEEK3;

            // Phi family
            case "phi-3":
            case "phi3":
                return PHI3;
            case "phi-4":
            case "phi4":
                return PHI4;

            // ChatGLM family
            case "chat-glm":
            case "chatglm":
            case "chatglm-3":
                return CHATGLM3;
            case "chatglm-4":
            case "glm-4":
                return CHATGLM4;

            // Other built-in templates
            case "command-r":
            case "commandr":
            case "command":
            case "cohere":
                return COMMAND_R;
            case "open-chat":
            case "openchat":
                return OPENCHAT;
            case "stable-lm":
            case "stablelm":
                return STABLELM;
            case "exaone-3":
            case "exaone3":
                return EXAONE3;
            case "exaone-4":
            case "exaone4":
                return EXAONE4;
            case "falcon-3":
            case "falcon3":
                return FALCON3;
            case "mini-cpm":
            case "minicpm":
                return MINICPM;
            case "hunyuan-dense":
            case "hunyuan":
                return HUNYUAN_DENSE;
            case "hunyuan-moe":
                return HUNYUAN_MOE;
            case "kimi-k2":
            case "kimi":
            case "moonshot":
                return KIMI_K2;
            case "grok-2":
            case "grok":
                return GROK2;

            // Legacy/custom templates (map to closest built-in or keep as-is)
            case "qwen":
            case "qwen-instruct":
            case "qwen-chat":
                return QWEN;
            case "wizard-lm":
            case "wizardlm":
            case "wizard":
                return WIZARDLM;
            case "neural-chat":
            case "neural":
                return NEURAL;
            case "nous-hermes":
            case "nous":
                return NOUS;

            default:
                // Check if it's already a valid llama-server template name
                List<String> builtinTemplates = Arrays.asList(
                    BAILING, BAILING_THINK, BAILING2, CHATGLM3, CHATGLM4, CHATML,
                    COMMAND_R, DEEPSEEK, DEEPSEEK2, DEEPSEEK3, EXAONE3, EXAONE4,
                    FALCON3, GEMMA, GIGACHAT, GLMEDGE, GPT_OSS, GRANITE, GROK2,
                    HUNYUAN_DENSE, HUNYUAN_MOE, KIMI_K2, LLAMA2, LLAMA2_SYS,
                    LLAMA2_SYS_BOS, LLAMA2_SYS_STRIP, LLAMA3, LLAMA4, MEGREZ,
                    MINICPM, MISTRAL_V1, MISTRAL_V3, MISTRAL_V3_TEKKEN, MISTRAL_V7,
                    MISTRAL_V7_TEKKEN, MONARCH, OPENCHAT, ORION, PANGU_EMBEDDED,
                    PHI3, PHI4, RWKV_WORLD, SEED_OSS, SMOLVLM, VICUNA, VICUNA_ORCA,
                    YANDEX, ZEPHYR
                );

                if (builtinTemplates.contains(normalized)) {
                    return normalized;
                }

                // Also check legacy templates
                List<String> legacyTemplates = Arrays.asList(
                    ALPACA, QWEN, NEURAL, SOLAR, ORCA, STABLELM, YI, NOUS, WIZARDLM
                );

                if (legacyTemplates.contains(normalized)) {
                    return normalized;
                }

                // Return as-is for unknown templates (let llama-server handle it)
                return normalized;
        }
    }
    
    /**
     * Detect template from chat template string in metadata.
     * Analyzes jinja template content to determine the template format.
     */
    public static String detectFromChatTemplateString(String chatTemplate) {
        if (chatTemplate == null) return null;

        // Keep original for unicode checks, lowercase for general checks
        String lowerTemplate = chatTemplate.toLowerCase();

        // DeepSeek family - check first since they use distinctive fullwidth unicode tokens
        // DeepSeek V3 uses: <｜User｜>, <｜Assistant｜>, <｜end▁of▁sentence｜>, <think>
        if (chatTemplate.contains("<｜User｜>") || chatTemplate.contains("<｜user｜>") ||
            chatTemplate.contains("<｜Assistant｜>") || chatTemplate.contains("<｜assistant｜>")) {
            // DeepSeek with <think> is V3
            if (chatTemplate.contains("<think>") || chatTemplate.contains("</think>")) {
                return DEEPSEEK3;
            }
            // Check for end_of_sentence token (V2/V3 style)
            if (chatTemplate.contains("<｜end▁of▁sentence｜>") || chatTemplate.contains("end_of_sentence")) {
                return DEEPSEEK3;
            }
            return DEEPSEEK2;
        }
        // Also check for deepseek keyword in template
        if (lowerTemplate.contains("deepseek")) {
            if (lowerTemplate.contains("v3") || lowerTemplate.contains("-3") ||
                chatTemplate.contains("<think>")) {
                return DEEPSEEK3;
            }
            if (lowerTemplate.contains("v2") || lowerTemplate.contains("-2")) {
                return DEEPSEEK2;
            }
            return DEEPSEEK;
        }

        // Llama family
        if (lowerTemplate.contains("<|header_start|>") || lowerTemplate.contains("llama4")) {
            return LLAMA4;
        }
        if (lowerTemplate.contains("<|start_header_id|>") && lowerTemplate.contains("<|end_header_id|>")) {
            return LLAMA3;
        }
        if (lowerTemplate.contains("[inst]") && lowerTemplate.contains("[/inst]")) {
            // Could be Llama2 or Mistral, check for mistral/ministral keyword
            if (lowerTemplate.contains("mistral") || lowerTemplate.contains("ministral")) {
                return MISTRAL_V7;
            }
            return LLAMA2;
        }

        // ChatML format
        if (lowerTemplate.contains("<|im_start|>") && lowerTemplate.contains("<|im_end|>")) {
            return CHATML;
        }

        // Phi family
        if (lowerTemplate.contains("<|user|>") && lowerTemplate.contains("<|assistant|>") &&
            lowerTemplate.contains("phi")) {
            if (lowerTemplate.contains("phi-4") || lowerTemplate.contains("phi4")) {
                return PHI4;
            }
            return PHI3;
        }

        // Gemma
        if (lowerTemplate.contains("<start_of_turn>") && lowerTemplate.contains("<end_of_turn>")) {
            return GEMMA;
        }

        // ChatGLM. The signature special token is [gMASK] (case-insensitive),
        // and the templates themselves contain "chatglm". The previous logic used
        // a misspelled "[gmaskt]" and an inverted guard that excluded real ChatGLM.
        if (lowerTemplate.contains("[gmask]") ||
            lowerTemplate.contains("chatglm") ||
            lowerTemplate.contains("glm-4") || lowerTemplate.contains("glm4")) {
            if (lowerTemplate.contains("glm-4") || lowerTemplate.contains("glm4") || lowerTemplate.contains("chatglm-4")) {
                return CHATGLM4;
            }
            return CHATGLM3;
        }

        // Zephyr - generic <|user|>/<|assistant|> without other identifiers
        if (lowerTemplate.contains("<|user|>") && lowerTemplate.contains("<|assistant|>")) {
            return ZEPHYR;
        }

        // OpenChat
        if (lowerTemplate.contains("gpt4 correct") || lowerTemplate.contains("gpt4_correct")) {
            return OPENCHAT;
        }

        // Command-R uses <start_of_turn> (no pipes). Gemma (handled above) also
        // uses <start_of_turn>, so only classify as Command-R when the template
        // explicitly mentions command-r.
        if (lowerTemplate.contains("command-r") || lowerTemplate.contains("command r")) {
            return COMMAND_R;
        }

        // Vicuna
        if (lowerTemplate.contains("user:") && lowerTemplate.contains("assistant:")) {
            if (lowerTemplate.contains("orca")) {
                return VICUNA_ORCA;
            }
            return VICUNA;
        }

        // Alpaca
        if (lowerTemplate.contains("### instruction:") || lowerTemplate.contains("### response:")) {
            return ALPACA;
        }

        // Falcon
        if (lowerTemplate.contains("falcon") && lowerTemplate.contains("user:")) {
            return FALCON3;
        }

        // RWKV
        if (lowerTemplate.contains("rwkv")) {
            return RWKV_WORLD;
        }

        // StableLM
        if (lowerTemplate.contains("<|system|>") && lowerTemplate.contains("stablelm")) {
            return STABLELM;
        }

        // Granite
        if (lowerTemplate.contains("granite")) {
            return GRANITE;
        }

        // EXAONE
        if (lowerTemplate.contains("[|user|]") || lowerTemplate.contains("exaone")) {
            if (lowerTemplate.contains("exaone4") || lowerTemplate.contains("exaone-4")) {
                return EXAONE4;
            }
            return EXAONE3;
        }

        // Monarch
        if (lowerTemplate.contains("monarch")) {
            return MONARCH;
        }

        // Orion
        if (lowerTemplate.contains("orion") || lowerTemplate.contains("<human>")) {
            return ORION;
        }

        // Qwen
        if (lowerTemplate.contains("qwen") || lowerTemplate.contains("<|im_start|>system")) {
            return QWEN;
        }

        // GPT-OSS - uses <|start|>role<|message|> format with <|end|> and <|channel|> tokens
        if (lowerTemplate.contains("<|start|>") && lowerTemplate.contains("<|message|>")) {
            return GPT_OSS;
        }

        return null;
    }
}