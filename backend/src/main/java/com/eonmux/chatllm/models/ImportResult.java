package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GraphQL response type for data import operations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportResult {
    private Boolean success;
    private String message;
    private ImportCounts result;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ImportCounts {
        private Integer conversations;
        private Integer messages;
        private Integer characters;
        private Integer settings;
        private List<String> errors;
    }
}
