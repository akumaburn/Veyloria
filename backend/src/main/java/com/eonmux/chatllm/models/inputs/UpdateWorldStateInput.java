package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("UpdateWorldStateInput")
public class UpdateWorldStateInput {
    private String currentScene;
    private String currentLocation;
    private String currentLocationId;
    private String currentArea;
    private String currentAreaId;
    private String timeOfDay;
    private Integer dayNumber;
    private String weather;
    private String mood;
    // globalFlags is optional flexible data; sent as JSON string by frontend if present
    private String globalFlags;
}
