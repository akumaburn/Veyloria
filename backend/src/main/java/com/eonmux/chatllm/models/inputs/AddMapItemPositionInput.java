package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("AddMapItemPositionInput")
public class AddMapItemPositionInput {
    private String mapId;
    private String conversationId;
    private String itemName;
    private String itemDescription;
    private String itemType;
    private Float x;
    private Float y;
    private Float z;
    private String linkedLocationId;
    private Boolean isDiscovered;
}
