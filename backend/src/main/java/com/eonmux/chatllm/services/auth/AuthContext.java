package com.eonmux.chatllm.services.auth;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication context containing user information from JWT token.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthContext {
    private UUID userId;
    private String username;
    private String displayName; // User's display name for group chat attribution
    @Builder.Default
    private Set<String> roles = ConcurrentHashMap.newKeySet(); // Thread-safe set
    private String tokenId;
    private Instant issuedAt;
    private Instant expiresAt;
    private String ipAddress; // Added for audit trail
    private String userAgent; // Added for audit trail
    private String sessionFingerprint; // Added for enhanced security
    
    /**
     * Set roles in a thread-safe manner.
     */
    public void setRoles(Set<String> roles) {
        if (roles == null) {
            this.roles = ConcurrentHashMap.newKeySet();
        } else {
            this.roles = ConcurrentHashMap.newKeySet(roles.size());
            this.roles.addAll(roles);
        }
    }
    
    /**
     * Get immutable view of roles.
     */
    public Set<String> getRoles() {
        return Collections.unmodifiableSet(roles);
    }
    
    /**
     * Check if user has a specific role.
     */
    public boolean hasRole(String role) {
        return role != null && roles != null && roles.contains(role);
    }
    
    /**
     * Check if user has any of the specified roles.
     */
    public boolean hasAnyRole(String... roles) {
        if (roles == null || this.roles == null) {
            return false;
        }
        for (String role : roles) {
            if (role != null && this.roles.contains(role)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if user has all of the specified roles.
     */
    public boolean hasAllRoles(String... roles) {
        if (roles == null || this.roles == null) {
            return false;
        }
        for (String role : roles) {
            if (role == null || !this.roles.contains(role)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Check if the token is expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
    
    /**
     * Get remaining time until expiration in seconds.
     */
    public long getRemainingTime() {
        if (expiresAt == null) {
            return 0;
        }
        long remaining = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }
    
    /**
     * Check if user is admin.
     */
    public boolean isAdmin() {
        return hasRole("ADMIN");
    }
    
    /**
     * Check if user is authenticated (not anonymous).
     */
    public boolean isAuthenticated() {
        return userId != null && !isExpired();
    }

    /**
     * Get effective display name (displayName if set, otherwise username).
     */
    public String getEffectiveDisplayName() {
        return displayName != null && !displayName.isEmpty() ? displayName : username;
    }
    
    /**
     * Create a sanitized copy for logging (no sensitive data).
     */
    public AuthContext sanitizedCopy() {
        return AuthContext.builder()
            .userId(userId)
            .username(username)
            .displayName(displayName)
            .roles(new HashSet<>(roles))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();
    }
    
    /**
     * Validate the context.
     */
    public boolean isValid() {
        return userId != null && 
               username != null && 
               roles != null && 
               !isExpired() &&
               tokenId != null;
    }
}