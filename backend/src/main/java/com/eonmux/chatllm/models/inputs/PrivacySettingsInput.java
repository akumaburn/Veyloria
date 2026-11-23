package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("PrivacySettingsInput")
public class PrivacySettingsInput {
    private Boolean shareAnalytics;
    private Boolean analytics;
    private Boolean shareData;
    private Boolean saveHistory;
    private Boolean encryptMessages;
    private Boolean clearOnLogout;
    private String exportFormat;
    private Integer dataRetentionDays;
    private Boolean publicProfile;
    private Boolean showOnlineStatus;
}
