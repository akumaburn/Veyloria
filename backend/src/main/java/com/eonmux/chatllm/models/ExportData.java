package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

import com.eonmux.chatllm.utils.Time4JHelper;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExportData {
    private List<Conversation> conversations;
    private List<Message> messages;
    private List<Character> characters;
    private Settings settings;
    @Builder.Default
    private String exportDate = Time4JHelper.nowUTCString();
    @Builder.Default
    private String version = "1.0";
}
