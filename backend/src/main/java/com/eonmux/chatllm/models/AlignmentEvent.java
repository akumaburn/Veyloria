package com.eonmux.chatllm.models;

import lombok.Data;

@Data
public class AlignmentEvent {
    private String eventId;
    private String action;
    private int lawDelta;
    private int goodDelta;
    private int previousLawfulness;
    private int previousGoodness;
    private int lawfulness;
    private int goodness;
    private String alignment;
    private String reason;
    private String createdAt;
}
