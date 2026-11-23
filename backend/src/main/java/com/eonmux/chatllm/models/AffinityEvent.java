package com.eonmux.chatllm.models;

import lombok.Data;

@Data
public class AffinityEvent {
    private String eventId;
    private String reaction;
    private int delta;
    private int previousPoints;
    private int points;
    private String reason;
    private String createdAt;
}
