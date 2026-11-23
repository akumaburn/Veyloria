package com.eonmux.chatllm.services.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JWTAuthProviderSecretTest {

    @Test void aMissingSecretIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> JWTAuthProvider.validateJwtSecret(null, true));
    }

    @Test void aShortSecretIsRejectedAndTheMessageNamesTheEnvironmentVariable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JWTAuthProvider.validateJwtSecret("too-short", true));

        assertTrue(error.getMessage().contains("JWT_SECRET"), error.getMessage());
    }

    @Test void aConfiguredSecretIsAcceptedInAnyMode() {
        assertDoesNotThrow(() -> JWTAuthProvider.validateJwtSecret(
                "a-real-secret-with-enough-entropy-123456", true));
        assertDoesNotThrow(() -> JWTAuthProvider.validateJwtSecret(
                "a-real-secret-with-enough-entropy-123456", false));
    }

    @Test void theFallbackSecretOnlyRunsInDevelopment() {
        assertDoesNotThrow(() -> JWTAuthProvider.validateJwtSecret(
                JWTAuthProvider.DEFAULT_SECRET, true));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JWTAuthProvider.validateJwtSecret(JWTAuthProvider.DEFAULT_SECRET, false));
        assertTrue(error.getMessage().contains("JWT_SECRET"), error.getMessage());
    }

    @Test void theDevelopmentSecretOnlyRunsInDevelopment() {
        assertDoesNotThrow(() -> JWTAuthProvider.validateJwtSecret(
                JWTAuthProvider.DEVELOPMENT_SECRET, true));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JWTAuthProvider.validateJwtSecret(JWTAuthProvider.DEVELOPMENT_SECRET, false));
        assertTrue(error.getMessage().contains("JWT_SECRET"), error.getMessage());
    }
}
