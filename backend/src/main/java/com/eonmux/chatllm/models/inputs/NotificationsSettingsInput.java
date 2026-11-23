package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("NotificationsSettingsInput")
public class NotificationsSettingsInput {
    private Boolean desktop;
    private Boolean enabled;
    private Boolean sound;
    private Boolean vibration;
    private Boolean mentions;
    private Boolean replies;
    private Boolean newConversations;
    private Boolean systemAlerts;
    private String schedule;
    private NotificationScheduleInput customSchedule;
    private Boolean messageAlerts;
    private Boolean email;
}
