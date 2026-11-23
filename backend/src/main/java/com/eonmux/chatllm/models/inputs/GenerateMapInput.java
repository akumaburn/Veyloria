package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("GenerateMapInput")
public class GenerateMapInput {
    private String conversationId;
    private String name;
    private String mapScale;
    private String seed;
    private Double waterLevel;
    private Integer locationCount;
    private String theme;
}
