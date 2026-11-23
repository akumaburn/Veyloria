package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComfyUIStatus {
    private Boolean connected;
    private Integer queueSize;
    private List<String> availableModels;
    private List<String> availableLoras;
}
