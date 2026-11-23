package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("UpdateSettingsInput")
public class UpdateSettingsInput {
    private LLMSettingsInput llm;
    private UISettingsInput ui;
    private NotificationsSettingsInput notifications;
    private PrivacySettingsInput privacy;
    private KeyboardSettingsInput keyboard;
}
