package com.eonmux.chatllm.utils;

import io.smallrye.mutiny.Uni;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Common validation utilities to eliminate duplicate validation code across services.
 */
public class ValidationHelper {

    /**
     * Common maximum lengths used across the application.
     */
    public static final int MAX_MESSAGE_LENGTH = 32768;      // 32KB
    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_TITLE_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_SYSTEM_PROMPT_LENGTH = 4096;
    public static final int MAX_INITIAL_PROMPT_LENGTH = 1024;

    /**
     * Validation result containing status and optional error message.
     */
    public record ValidationResult(boolean valid, String error) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String error) {
            return new ValidationResult(false, error);
        }

        public Uni<Void> toUni() {
            if (valid) {
                return Uni.createFrom().voidItem();
            }
            return Uni.createFrom().failure(new RuntimeException(error));
        }
    }

    // ================================================================
    // String Validation
    // ================================================================

    /**
     * Check if a string is null or empty (after trimming).
     *
     * @param value The string to check
     * @return true if null or empty
     */
    public static boolean isNullOrEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Check if a string is not null and not empty.
     *
     * @param value The string to check
     * @return true if has content
     */
    public static boolean hasContent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Truncate a string to a maximum length.
     *
     * @param value     The string to truncate
     * @param maxLength Maximum allowed length
     * @return Truncated string or original if shorter
     */
    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /**
     * Validate and truncate a required string field.
     *
     * @param value       The value to validate
     * @param fieldName   The field name for error messages
     * @param maxLength   Maximum allowed length
     * @return ValidationResult with truncated value or error
     */
    public static ValidationResult validateRequired(String value, String fieldName, int maxLength) {
        if (isNullOrEmpty(value)) {
            return ValidationResult.fail(fieldName + " is required");
        }
        return ValidationResult.ok();
    }

    /**
     * Validate a string doesn't exceed max length.
     *
     * @param value     The value to check
     * @param fieldName The field name for error messages
     * @param maxLength Maximum allowed length
     * @return ValidationResult
     */
    public static ValidationResult validateMaxLength(String value, String fieldName, int maxLength) {
        if (value != null && value.length() > maxLength) {
            return ValidationResult.fail(fieldName + " exceeds maximum length of " + maxLength + " characters");
        }
        return ValidationResult.ok();
    }

    // ================================================================
    // Message Role Validation
    // ================================================================

    private static final List<String> VALID_ROLES = List.of("user", "assistant", "system");

    /**
     * Validate a message role.
     *
     * @param role The role to validate
     * @return ValidationResult
     */
    public static ValidationResult validateMessageRole(String role) {
        if (role == null || !VALID_ROLES.contains(role)) {
            return ValidationResult.fail("Invalid message role: " + role + ". Must be one of: " + String.join(", ", VALID_ROLES));
        }
        return ValidationResult.ok();
    }

    /**
     * Check if a role is valid.
     *
     * @param role The role to check
     * @return true if valid
     */
    public static boolean isValidRole(String role) {
        return role != null && VALID_ROLES.contains(role);
    }

    // ================================================================
    // Numeric Validation
    // ================================================================

    /**
     * Validate a number is within a range.
     *
     * @param value     The value to check
     * @param fieldName The field name for error messages
     * @param min       Minimum allowed value (inclusive)
     * @param max       Maximum allowed value (inclusive)
     * @return ValidationResult
     */
    public static ValidationResult validateRange(int value, String fieldName, int min, int max) {
        if (value < min || value > max) {
            return ValidationResult.fail(fieldName + " must be between " + min + " and " + max);
        }
        return ValidationResult.ok();
    }

    /**
     * Validate a rating value (1-5).
     *
     * @param rating The rating to validate
     * @return ValidationResult
     */
    public static ValidationResult validateRating(int rating) {
        return validateRange(rating, "Rating", 1, 5);
    }

    // ================================================================
    // LLM Parameter Validation
    // ================================================================

    /**
     * Validate LLM temperature parameter (0.0 - 2.0).
     *
     * @param temperature The temperature value to validate
     * @return ValidationResult
     */
    public static ValidationResult validateTemperature(Float temperature) {
        if (temperature == null) {
            return ValidationResult.ok(); // Null is acceptable (use default)
        }
        if (temperature < 0.0f || temperature > 2.0f) {
            return ValidationResult.fail("Temperature must be between 0.0 and 2.0");
        }
        return ValidationResult.ok();
    }

    /**
     * Validate LLM top_p parameter (0.0 - 1.0).
     *
     * @param topP The top_p value to validate
     * @return ValidationResult
     */
    public static ValidationResult validateTopP(Float topP) {
        if (topP == null) {
            return ValidationResult.ok(); // Null is acceptable (use default)
        }
        if (topP < 0.0f || topP > 1.0f) {
            return ValidationResult.fail("Top P must be between 0.0 and 1.0");
        }
        return ValidationResult.ok();
    }

    /**
     * Validate LLM top_k parameter (1 - 100).
     *
     * @param topK The top_k value to validate
     * @return ValidationResult
     */
    public static ValidationResult validateTopK(Integer topK) {
        if (topK == null) {
            return ValidationResult.ok(); // Null is acceptable (use default)
        }
        if (topK < 1 || topK > 100) {
            return ValidationResult.fail("Top K must be between 1 and 100");
        }
        return ValidationResult.ok();
    }

    /**
     * Validate LLM context size parameter (128 - 128000).
     *
     * @param contextSize The context size to validate
     * @return ValidationResult
     */
    public static ValidationResult validateContextSize(Integer contextSize) {
        if (contextSize == null) {
            return ValidationResult.ok(); // Null is acceptable (use default)
        }
        if (contextSize < 128 || contextSize > 128000) {
            return ValidationResult.fail("Context size must be between 128 and 128000");
        }
        return ValidationResult.ok();
    }

    /**
     * Validate LLM max tokens parameter (1 - 32000).
     *
     * @param maxTokens The max tokens to validate
     * @return ValidationResult
     */
    public static ValidationResult validateMaxTokens(Integer maxTokens) {
        if (maxTokens == null) {
            return ValidationResult.ok(); // Null is acceptable (use default)
        }
        if (maxTokens < 1 || maxTokens > 32000) {
            return ValidationResult.fail("Max tokens must be between 1 and 32000");
        }
        return ValidationResult.ok();
    }

    // ================================================================
    // Settings Validation
    // ================================================================

    private static final List<String> VALID_THEMES = List.of("light", "dark", "system");
    private static final List<String> VALID_LANGUAGES = List.of("en", "es", "fr", "de", "it", "pt", "ru", "zh", "ja", "ko");

    /**
     * Check if a theme is valid.
     *
     * @param theme The theme to check
     * @return true if valid theme
     */
    public static boolean isValidTheme(String theme) {
        return theme != null && VALID_THEMES.contains(theme);
    }

    /**
     * Check if a language code is valid.
     *
     * @param language The language code to check
     * @return true if valid language code
     */
    public static boolean isValidLanguage(String language) {
        return language != null && VALID_LANGUAGES.contains(language);
    }

    /**
     * Validate a theme setting.
     *
     * @param theme The theme to validate
     * @return ValidationResult
     */
    public static ValidationResult validateTheme(String theme) {
        if (!isValidTheme(theme)) {
            return ValidationResult.fail("Invalid theme: " + theme + ". Must be one of: " + String.join(", ", VALID_THEMES));
        }
        return ValidationResult.ok();
    }

    /**
     * Validate a language setting.
     *
     * @param language The language code to validate
     * @return ValidationResult
     */
    public static ValidationResult validateLanguage(String language) {
        if (!isValidLanguage(language)) {
            return ValidationResult.fail("Invalid language: " + language + ". Must be one of: " + String.join(", ", VALID_LANGUAGES));
        }
        return ValidationResult.ok();
    }

    // ================================================================
    // Pattern Validation
    // ================================================================

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );

    private static final Pattern USERNAME_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9_-]{3,50}$"
    );

    /**
     * Validate a UUID string.
     *
     * @param uuid The UUID to validate
     * @return true if valid UUID format
     */
    public static boolean isValidUUID(String uuid) {
        return uuid != null && UUID_PATTERN.matcher(uuid).matches();
    }

    /**
     * Validate an email address.
     *
     * @param email The email to validate
     * @return true if valid email format
     */
    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * Validate a username.
     *
     * @param username The username to validate
     * @return ValidationResult
     */
    public static ValidationResult validateUsername(String username) {
        if (isNullOrEmpty(username)) {
            return ValidationResult.fail("Username is required");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return ValidationResult.fail("Username must be 3-50 characters and contain only letters, numbers, underscores, and hyphens");
        }
        return ValidationResult.ok();
    }

    // ================================================================
    // Builder Pattern for Multiple Validations
    // ================================================================

    /**
     * Fluent validation builder for chaining multiple validations.
     */
    public static class Validator {
        private final List<ValidationResult> results = new ArrayList<>();

        public Validator required(String value, String fieldName) {
            if (isNullOrEmpty(value)) {
                results.add(ValidationResult.fail(fieldName + " is required"));
            }
            return this;
        }

        public Validator maxLength(String value, String fieldName, int maxLength) {
            if (value != null && value.length() > maxLength) {
                results.add(ValidationResult.fail(fieldName + " exceeds maximum length of " + maxLength));
            }
            return this;
        }

        public Validator range(int value, String fieldName, int min, int max) {
            if (value < min || value > max) {
                results.add(ValidationResult.fail(fieldName + " must be between " + min + " and " + max));
            }
            return this;
        }

        public Validator matches(String value, Pattern pattern, String errorMessage) {
            if (value != null && !pattern.matcher(value).matches()) {
                results.add(ValidationResult.fail(errorMessage));
            }
            return this;
        }

        public Validator custom(Predicate<Void> predicate, String errorMessage) {
            if (!predicate.test(null)) {
                results.add(ValidationResult.fail(errorMessage));
            }
            return this;
        }

        /**
         * Get all validation errors.
         *
         * @return List of error messages
         */
        public List<String> getErrors() {
            return results.stream()
                .filter(r -> !r.valid())
                .map(ValidationResult::error)
                .toList();
        }

        /**
         * Check if validation passed.
         *
         * @return true if no errors
         */
        public boolean isValid() {
            return results.stream().allMatch(ValidationResult::valid);
        }

        /**
         * Get the first error message.
         *
         * @return First error or null if valid
         */
        public String getFirstError() {
            return results.stream()
                .filter(r -> !r.valid())
                .map(ValidationResult::error)
                .findFirst()
                .orElse(null);
        }

        /**
         * Convert to Future - succeeds if valid, fails with first error if not.
         *
         * @return Future<Void>
         */
        public Uni<Void> toUni() {
            String error = getFirstError();
            if (error != null) {
                return Uni.createFrom().failure(new RuntimeException(error));
            }
            return Uni.createFrom().voidItem();
        }
    }

    /**
     * Create a new validator builder.
     *
     * @return New Validator instance
     */
    public static Validator validate() {
        return new Validator();
    }
}
