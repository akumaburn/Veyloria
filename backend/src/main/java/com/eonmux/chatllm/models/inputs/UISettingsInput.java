package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("UISettingsInput")
public class UISettingsInput {
    private String theme;
    private String fontSize;
    private String fontFamily;
    private Boolean compactMode;
    private Boolean showTimestamps;
    private Boolean showTokenCount;
    private Boolean showModelInfo;
    private Boolean enterToSend;
    private Boolean soundEnabled;
    private Boolean notificationsEnabled;
    private Boolean autoScroll;
    private Boolean messageGrouping;
    private Boolean syntaxHighlighting;
    private Boolean markdownRendering;
    private Boolean emojiPicker;
    private Boolean spellCheck;
    private Boolean autoSave;
    private Integer autoSaveInterval;
    private String sidebarPosition;
    private Boolean sidebarCollapsed;
    private String messageLayout;
    private String language;
    private String codeTheme;
    private String editorTheme;
    private Boolean showLineNumbers;
    private Boolean wordWrap;
}
