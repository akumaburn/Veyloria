package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("UpdateEntityPositionInput")
public class UpdateEntityPositionInput {
    private String mapId;
    private String characterId;
    private String userId;
    private String locationId;
    private Float x;
    private Float y;
    private Float z;
}
