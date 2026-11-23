package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("MoveCharacterOnMapInput")
public class MoveCharacterOnMapInput {
    private String mapId;
    private String characterId;
    private String userId;
    private Float x;
    private Float y;
    private Float z;
    private String locationId;
}
