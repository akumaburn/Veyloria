package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemNotification {
    private String id;
    private String type;
    private String title;
    private String message;
    private String severity;
    private String timestamp;
}
