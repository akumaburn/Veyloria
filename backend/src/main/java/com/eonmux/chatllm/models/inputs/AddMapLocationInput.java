package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("AddMapLocationInput")
public class AddMapLocationInput {
    private String mapId;
    private String name;
    private String description;
    private Float x;
    private Float y;
    private Float z;
    private String icon;
    private String locationType;
    private String linkedSceneId;
    private Boolean isDiscovered;
}
