package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("WorldMapInput")
public class WorldMapInput {
    private String conversationId;
    private String name;
    private String mapType;
    private String mapScale;
    private Integer gridWidth;
    private Integer gridHeight;
}
