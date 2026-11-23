# Prompt Template Guide

## Overview

This guide explains the different prompt templates available in Veyloria and when to use each one.

## Template Selection

The system can auto-detect the appropriate template based on your model filename. If auto-detection fails or you want to
override it, set `llm.prompt.template` in `application.properties`.

## Available Templates

### 1. ALPACA

- **Format**: Classic Alpaca instruction format
- **Use for**: Alpaca-based models
- **Example models**: alpaca-7b, alpaca-13b

### 2. ALPACA_SYSTEM

- **Format**: Alpaca with system prompt support
- **Use for**: Alpaca models that support system prompts

### 3. LLAMA_V2

- **Format**: Llama 2 chat format with <<SYS>> tags
- **Use for**: Llama 2 chat models
- **Example models**: llama-2-7b-chat, llama-2-13b-chat

### 4. LLAMA3

- **Format**: Llama 3 chat format with header tags
- **Use for**: Llama 3 models
- **Example models**: llama-3-8b, llama-3-70b
- **Auto-detected**: When filename contains "llama-3" or "llama3"

### 5. CHATML

- **Format**: ChatML format with |im_start| and |im_end| tags
- **Use for**: Models trained with ChatML format
- **Default**: This is the default if no template is specified

### 6. OPENCHAT

- **Format**: OpenChat format with |end_of_turn| tags
- **Use for**: OpenChat models

### 7. MISTRAL / MIXTRAL

- **Format**: Mistral instruction format with [INST] tags
- **Use for**: Mistral and Mixtral models
- **Example models**: mistral-7b-instruct, mixtral-8x7b
- **Auto-detected**: When filename contains "mistral" or "mixtral"

### 8. GEMMA

- **Format**: Google Gemma format with turn tags
- **Use for**: Google's Gemma models
- **Auto-detected**: When filename contains "gemma"

### 9. PHI3

- **Format**: Microsoft Phi-3 format
- **Use for**: Microsoft's Phi-3 models
- **Auto-detected**: When filename contains "phi-3" or "phi3"

### 10. COMMAND_R

- **Format**: Cohere Command-R format with turn tokens
- **Use for**: Cohere's Command-R models
- **Auto-detected**: When filename contains "command-r"

### 11. QWEN_INSTRUCT

- **Format**: Qwen instruction format
- **Use for**: Qwen instruct models
- **Auto-detected**: When filename contains "qwen" and "instruct"

### 12. NEMOTRON

- **Format**: Nemotron/OpenCodeReasoning format
- **Use for**: Nvidia Nemotron and OpenCodeReasoning models
- **Auto-detected**: When filename contains "nemotron" or "opencodereasoning"

### 13. ZEPHYR

- **Format**: Zephyr chat format
- **Use for**: Zephyr models
- **Auto-detected**: When filename contains "zephyr"

### 14. VICUNA

- **Format**: Vicuna conversation format
- **Use for**: Vicuna models
- **Auto-detected**: When filename contains "vicuna"

### 15. WIZARDLM

- **Format**: WizardLM format
- **Use for**: WizardLM models
- **Auto-detected**: When filename contains "wizardlm"

### 16. ORCA

- **Format**: Orca instruction format
- **Use for**: Orca models
- **Auto-detected**: When filename contains "orca"

### 17. INSTRUCT

- **Format**: Basic instruction format (no conversation history)
- **Use for**: Pure instruction models that don't support conversation

### 18. INSTRUCT_WITH_SYSTEM

- **Format**: Instruction format with system prompt support
- **Use for**: Instruction models that support system prompts

### 19. PLAIN

- **Format**: No formatting at all
- **Use for**: Models that handle their own formatting or when you want complete control

### 20. DEEPSEEK

- **Format**: DeepSeek chat format
- **Use for**: DeepSeek models

### 21. V7_TEKKEN

- **Format**: V7 Tekken format
- **Use for**: Specific V7 models

### 22. INTEL_NEURALCHAT

- **Format**: Intel NeuralChat format
- **Use for**: Intel's NeuralChat models

### 23. JINJA2

- **Format**: Same as CHATML but designed for Jinja2 template compatibility
- **Use for**: Models that use Jinja2 templates

## Configuration Examples

### Setting a specific template:

```properties
llm.prompt.template=LLAMA3
```

### Using minimal context (for instruction models):

```properties
llm.context.minimal=true
```

### Using custom context:

```properties
llm.context.custom=You are an expert programmer. Provide detailed, well-commented code.
```

### Disabling conversation history (recommended for instruction models):

```properties
llm.conversation.history.enabled=false
```

## Troubleshooting

1. **Model returns empty responses**:
    - Try switching to INSTRUCT or PLAIN template
    - Disable conversation history
    - Use minimal context

2. **Model includes template tokens in output**:
    - Switch to a different template
    - Try PLAIN template for maximum compatibility

3. **Model doesn't follow instructions**:
    - Ensure you're using the correct template for your model
    - Check if your model is instruction-tuned or conversational
    - Adjust the context/system prompt

## Testing Templates

To test which template works best for your model:

1. Set `llm.prompt.template` to the template you want to test
2. Set `llm.context.minimal=true` for instruction models
3. Try a simple prompt like "What is 2+2?"
4. If the model responds correctly, try more complex prompts
5. If not, try a different template

## Auto-Detection

The system automatically detects templates based on model filenames. To disable auto-detection and force a specific
template, explicitly set the template in `application.properties`.
## Roleplay decision contracts

A chat serialization template controls how system/user/assistant messages reach
the model; it does not relax a decision schema or authorize game effects.
Judgment calls use `BaseAgent.callJudgment` and `JudgmentProtocol` regardless of
selected model/template: exactly one complete schema-valid result per request,
one schema-repair retry, then an explicit failure. Encounter, travel,
interaction, each bookkeeping operation, and review verdicts remain separate.
Narration, character prose and generated options cannot overwrite fixed results.
See [atomic LLM decisions](DEVELOPER_GUIDE.md#atomic-llm-decisions) for the
current contracts and handlers.
