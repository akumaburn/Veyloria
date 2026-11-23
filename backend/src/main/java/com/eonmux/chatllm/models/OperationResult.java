package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL response type for simple success/message operations (changePassword, etc.).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationResult {
    private Boolean success;
    private String message;
}
