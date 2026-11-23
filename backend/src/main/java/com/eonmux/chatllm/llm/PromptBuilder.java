package com.eonmux.chatllm.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds prompts for different LLM templates.
 * Supports 23+ prompt templates with proper formatting.
 */
public class PromptBuilder {

    private final String template;
    private String systemPrompt;
    private String reasoningEffort = "high"; // GPT-OSS reasoning effort: low, medium, high
    private final List<Message> messages = new ArrayList<>();

    public PromptBuilder(String template) {
        this.template = template != null ? template : "CHATML";
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void setReasoningEffort(String effort) {
        this.reasoningEffort = effort != null ? effort : "high";
    }
    
    public void addUserMessage(String content) {
        messages.add(new Message("user", content));
    }
    
    public void addAssistantMessage(String content) {
        messages.add(new Message("assistant", content));
    }
    
    public void addSystemMessage(String content) {
        messages.add(new Message("system", content));
    }
    
    /**
     * Build the final prompt based on template.
     */
    public String build() {
        switch (template.toUpperCase()) {
            case "CHATML":
                return buildChatML();
            case "LLAMA3":
            case "LLAMA_3":
                return buildLlama3();
            case "MISTRAL":
            case "MISTRAL-V1":
            case "MISTRAL_V1":
                return buildMistral();
            case "MISTRAL-V3":
            case "MISTRAL_V3":
            case "MISTRAL-V3-TEKKEN":
            case "MISTRAL_V3_TEKKEN":
            case "MISTRAL-V7":
            case "MISTRAL_V7":
            case "MISTRAL-V7-TEKKEN":
            case "MISTRAL_V7_TEKKEN":
                return buildMistralV7();
            case "ALPACA":
                return buildAlpaca();
            case "VICUNA":
                return buildVicuna();
            case "WIZARDLM":
                return buildWizardLM();
            case "PHI3":
            case "PHI_3":
                return buildPhi3();
            case "GEMMA":
                return buildGemma();
            case "COMMAND_R":
                return buildCommandR();
            case "ZEPHYR":
                return buildZephyr();
            case "OPENCHAT":
                return buildOpenChat();
            case "DEEPSEEK":
                return buildDeepSeek();
            case "QWEN":
            case "QWEN_INSTRUCT":
                return buildQwen();
            case "NEMOTRON":
                return buildNemotron();
            case "ORCA":
                return buildOrca();
            case "INSTRUCT":
                return buildInstruct();
            case "PLAIN":
                return buildPlain();
            case "GPT-OSS":
            case "GPT_OSS":
            case "GPTOSS":
                return buildGptOss();
            default:
                return buildChatML(); // Default to ChatML
        }
    }
    
    private String buildChatML() {
        StringBuilder prompt = new StringBuilder();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("<|im_start|>system\n")
                  .append(systemPrompt)
                  .append("<|im_end|>\n");
        }
        
        for (Message msg : messages) {
            prompt.append("<|im_start|>").append(msg.role).append("\n")
                  .append(msg.content)
                  .append("<|im_end|>\n");
        }
        
        prompt.append("<|im_start|>assistant\n");
        
        return prompt.toString();
    }
    
    private String buildLlama3() {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("<|begin_of_text|>");
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("<|start_header_id|>system<|end_header_id|>\n\n")
                  .append(systemPrompt)
                  .append("<|eot_id|>");
        }
        
        for (Message msg : messages) {
            prompt.append("<|start_header_id|>").append(msg.role).append("<|end_header_id|>\n\n")
                  .append(msg.content)
                  .append("<|eot_id|>");
        }
        
        prompt.append("<|start_header_id|>assistant<|end_header_id|>\n\n");
        
        return prompt.toString();
    }
    
    private String buildMistral() {
        StringBuilder prompt = new StringBuilder();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("<s>[INST] ").append(systemPrompt).append(" [/INST]</s>");
        }

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if ("user".equals(msg.role)) {
                prompt.append("<s>[INST] ").append(msg.content).append(" [/INST]");

                // Look for next assistant message
                if (i + 1 < messages.size() && "assistant".equals(messages.get(i + 1).role)) {
                    prompt.append(messages.get(i + 1).content).append("</s>");
                    i++; // Skip the assistant message
                }
            }
        }

        return prompt.toString();
    }

    /**
     * Build prompt for Mistral V3/V7 format (used by Ministral and newer Mistral models).
     * Format:
     * - BOS: <s>
     * - System: [SYSTEM_PROMPT]...content...[/SYSTEM_PROMPT]
     * - User: [INST]...content...[/INST]
     * - Assistant: ...content...</s>
     */
    private String buildMistralV7() {
        StringBuilder prompt = new StringBuilder();

        // Begin with BOS token
        prompt.append("<s>");

        // System prompt uses [SYSTEM_PROMPT] tags
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("[SYSTEM_PROMPT]")
                  .append(systemPrompt)
                  .append("[/SYSTEM_PROMPT]");
        }

        // Process messages
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if ("user".equals(msg.role)) {
                prompt.append("[INST]").append(msg.content).append("[/INST]");
            } else if ("assistant".equals(msg.role)) {
                // Assistant messages followed by EOS token
                prompt.append(msg.content).append("</s>");
            }
        }

        // No trailing tokens - model generates directly after [/INST]
        return prompt.toString();
    }
    
    private String buildAlpaca() {
        StringBuilder prompt = new StringBuilder();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("### System:\n").append(systemPrompt).append("\n\n");
        }
        
        // Get the last user message as instruction
        String instruction = "";
        String input = "";
        
        for (Message msg : messages) {
            if ("user".equals(msg.role)) {
                instruction = msg.content;
            }
        }
        
        prompt.append("### Instruction:\n").append(instruction).append("\n\n");
        
        if (!input.isEmpty()) {
            prompt.append("### Input:\n").append(input).append("\n\n");
        }
        
        prompt.append("### Response:\n");
        
        return prompt.toString();
    }
    
    private String buildVicuna() {
        StringBuilder prompt = new StringBuilder();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("SYSTEM: ").append(systemPrompt).append("\n\n");
        }
        
        for (Message msg : messages) {
            if ("user".equals(msg.role)) {
                prompt.append("USER: ").append(msg.content).append("\n");
            } else if ("assistant".equals(msg.role)) {
                prompt.append("ASSISTANT: ").append(msg.content).append("\n");
            }
        }
        
        prompt.append("ASSISTANT:");
        
        return prompt.toString();
    }
    
    private String buildWizardLM() {
        StringBuilder prompt = new StringBuilder();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append(systemPrompt).append("\n\n");
        }
        
        for (Message msg : messages) {
            if ("user".equals(msg.role)) {
                prompt.append("### User: ").append(msg.content).append("\n");
            } else if ("assistant".equals(msg.role)) {
                prompt.append("### Assistant: ").append(msg.content).append("\n");
            }
        }
        
        prompt.append("### Assistant:");
        
        return prompt.toString();
    }
    
    private String buildPhi3() {
        StringBuilder prompt = new StringBuilder();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("<|system|>\n").append(systemPrompt).append("<|end|>\n");
        }
        
        for (Message msg : messages) {
            prompt.append("<|").append(msg.role).append("|>\n")
                  .append(msg.content)
                  .append("<|end|>\n");
        }
        
        prompt.append("<|assistant|>\n");
        
        return prompt.toString();
    }
    
    private String buildGemma() {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("<bos>");
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("<start_of_turn>system\n")
                  .append(systemPrompt)
                  .append("<end_of_turn>\n");
        }
        
        for (Message msg : messages) {
            String role = "user".equals(msg.role) ? "user" : "model";
            prompt.append("<start_of_turn>").append(role).append("\n")
                  .append(msg.content)
                  .append("<end_of_turn>\n");
        }
        
        prompt.append("<start_of_turn>model\n");
        
        return prompt.toString();
    }
    
    private String buildCommandR() {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("<BOS_TOKEN>");
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("<|START_OF_TURN_TOKEN|><|SYSTEM_TOKEN|>")
                  .append(systemPrompt)
                  .append("<|END_OF_TURN_TOKEN|>");
        }
        
        for (Message msg : messages) {
            String tokenType = "user".equals(msg.role) ? "USER" : "CHATBOT";
            prompt.append("<|START_OF_TURN_TOKEN|><|").append(tokenType).append("_TOKEN|>")
                  .append(msg.content)
                  .append("<|END_OF_TURN_TOKEN|>");
        }
        
        prompt.append("<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>");
        
        return prompt.toString();
    }
    
    private String buildZephyr() {
        StringBuilder prompt = new StringBuilder();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("<|system|>\n").append(systemPrompt).append("</s>\n");
        }
        
        for (Message msg : messages) {
            prompt.append("<|").append(msg.role).append("|>\n")
                  .append(msg.content)
                  .append("</s>\n");
        }
        
        prompt.append("<|assistant|>\n");
        
        return prompt.toString();
    }
    
    private String buildOpenChat() {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("GPT4 Correct ");
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("System: ").append(systemPrompt).append("<|end_of_turn|>");
        }
        
        for (Message msg : messages) {
            String role = "user".equals(msg.role) ? "User" : "Assistant";
            prompt.append(role).append(": ").append(msg.content).append("<|end_of_turn|>");
        }
        
        prompt.append("Assistant:");
        
        return prompt.toString();
    }
    
    private String buildDeepSeek() {
        StringBuilder prompt = new StringBuilder();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append(systemPrompt).append("\n\n");
        }
        
        for (Message msg : messages) {
            if ("user".equals(msg.role)) {
                prompt.append("User: ").append(msg.content).append("\n\n");
            } else if ("assistant".equals(msg.role)) {
                prompt.append("Assistant: ").append(msg.content).append("\n\n");
            }
        }
        
        prompt.append("Assistant:");
        
        return prompt.toString();
    }
    
    private String buildQwen() {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("<|im_start|>system\n");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append(systemPrompt);
        } else {
            prompt.append("You are a helpful assistant.");
        }
        prompt.append("<|im_end|>\n");
        
        for (Message msg : messages) {
            prompt.append("<|im_start|>").append(msg.role).append("\n")
                  .append(msg.content)
                  .append("<|im_end|>\n");
        }
        
        prompt.append("<|im_start|>assistant\n");
        
        return prompt.toString();
    }
    
    private String buildNemotron() {
        StringBuilder prompt = new StringBuilder();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("<extra_id_0>System\n").append(systemPrompt).append("\n\n");
        }
        
        for (Message msg : messages) {
            if ("user".equals(msg.role)) {
                prompt.append("<extra_id_1>User\n").append(msg.content).append("\n");
            } else if ("assistant".equals(msg.role)) {
                prompt.append("<extra_id_1>Assistant\n").append(msg.content).append("\n");
            }
        }
        
        prompt.append("<extra_id_1>Assistant\n");
        
        return prompt.toString();
    }
    
    private String buildOrca() {
        StringBuilder prompt = new StringBuilder();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("### System:\n").append(systemPrompt).append("\n\n");
        }
        
        for (Message msg : messages) {
            if ("user".equals(msg.role)) {
                prompt.append("### User:\n").append(msg.content).append("\n\n");
            } else if ("assistant".equals(msg.role)) {
                prompt.append("### Assistant:\n").append(msg.content).append("\n\n");
            }
        }
        
        prompt.append("### Assistant:\n");
        
        return prompt.toString();
    }
    
    private String buildInstruct() {
        // Simple instruction format
        StringBuilder prompt = new StringBuilder();
        
        if (!messages.isEmpty()) {
            Message lastMessage = messages.get(messages.size() - 1);
            if ("user".equals(lastMessage.role)) {
                prompt.append(lastMessage.content);
            }
        }
        
        return prompt.toString();
    }
    
    private String buildPlain() {
        // Plain text format - just concatenate messages
        StringBuilder prompt = new StringBuilder();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append(systemPrompt).append("\n\n");
        }

        for (Message msg : messages) {
            prompt.append(msg.content).append("\n");
        }

        return prompt.toString();
    }

    private String buildGptOss() {
        // GPT-OSS Harmony format: <|start|>role<|message|>content<|end|>
        // Assistant uses channels: <|start|>assistant<|channel|>channelname<|message|>content<|end|>
        // Channels: analysis (chain-of-thought, private), commentary (tool calls), final (response)
        // Generation ends with <|return|> token, NOT <|end|>
        //
        // Per OpenAI Harmony spec (github.com/openai/harmony):
        // System prompt must include: Knowledge cutoff, Current date, Reasoning effort
        StringBuilder prompt = new StringBuilder();

        // Build system message with Harmony format
        prompt.append("<|start|>system<|message|>");

        // Use custom system prompt if provided, otherwise use GPT-OSS default
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append(systemPrompt);
            // Only append metadata if not already present in custom prompt
            if (!systemPrompt.contains("Knowledge cutoff:")) {
                prompt.append("\n\nKnowledge cutoff: 2024-06\n");
                prompt.append("Current date: ").append(java.time.LocalDate.now()).append("\n\n");
                prompt.append("Reasoning: ").append(reasoningEffort);
            }
        } else {
            // Default GPT-OSS system prompt per OpenAI spec
            prompt.append("You are ChatGPT, a large language model trained by OpenAI.\n\n");
            prompt.append("Knowledge cutoff: 2024-06\n\n");
            prompt.append("Current date: ").append(java.time.LocalDate.now()).append("\n\n");
            prompt.append("Reasoning: ").append(reasoningEffort);
        }
        prompt.append("<|end|>");

        for (Message msg : messages) {
            if ("user".equals(msg.role)) {
                prompt.append("<|start|>user<|message|>")
                      .append(msg.content)
                      .append("<|end|>");
            } else if ("assistant".equals(msg.role)) {
                // Check if content already has channel tokens (pre-formatted)
                if (msg.content.contains("<|channel|>")) {
                    prompt.append("<|start|>assistant")
                          .append(msg.content)
                          .append("<|end|>");
                } else {
                    // For historical assistant messages during inference, CoT is dropped
                    // Only render the final channel response
                    prompt.append("<|start|>assistant<|channel|>final<|message|>")
                          .append(msg.content)
                          .append("<|end|>");
                }
            } else if ("system".equals(msg.role)) {
                // Additional system messages use developer role in Harmony
                prompt.append("<|start|>developer<|message|>");
                prompt.append("# Instructions\n\n");
                prompt.append(msg.content);
                prompt.append("<|end|>");
            }
        }

        // Generation prompt - model chooses channel itself (analysis for thinking, final for response)
        // Do NOT include <|channel|> - the model outputs that
        prompt.append("<|start|>assistant");

        return prompt.toString();
    }

    /**
     * Internal message class.
     */
    private static class Message {
        final String role;
        final String content;
        
        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}