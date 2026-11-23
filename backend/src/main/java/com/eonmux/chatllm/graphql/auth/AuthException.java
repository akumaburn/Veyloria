package com.eonmux.chatllm.graphql.auth;

/**
 * Unchecked exception thrown for authentication/authorization failures.
 * Listed in mp.graphql.showErrorMessage so the message is always sent to
 * the client, even when showErrorDetails=false.
 */
public class AuthException extends RuntimeException {
    public AuthException(String message) {
        super(message);
    }
}
