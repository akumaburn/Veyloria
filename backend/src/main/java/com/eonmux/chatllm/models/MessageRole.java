package com.eonmux.chatllm.models;

/**
 * Enum representing the role of a message sender.
 */
public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    FUNCTION,
    TOOL;
    
    /**
     * Get role as lowercase string.
     */
    public String toLowerCaseString() {
        return this.name().toLowerCase();
    }
    
    /**
     * Parse from string value.
     */
    public static MessageRole fromString(String role) {
        if (role == null) return USER;
        
        try {
            return valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Handle common variations
            switch (role.toLowerCase()) {
                case "user":
                    return USER;
                case "assistant":
                case "ai":
                case "bot":
                    return ASSISTANT;
                case "system":
                    return SYSTEM;
                case "function":
                    return FUNCTION;
                case "tool":
                    return TOOL;
                default:
                    return USER;
            }
        }
    }
    
    /**
     * Check if this is a human role.
     */
    public boolean isHuman() {
        return this == USER;
    }
    
    /**
     * Check if this is an AI role.
     */
    public boolean isAI() {
        return this == ASSISTANT || this == SYSTEM;
    }
    
    /**
     * Check if this is a special role.
     */
    public boolean isSpecial() {
        return this == SYSTEM || this == FUNCTION || this == TOOL;
    }
}