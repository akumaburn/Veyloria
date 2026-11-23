package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("AddInventoryItemInput")
public class AddInventoryItemInput {
    private String conversationId;
    private String characterId;
    private String userId;
    private String itemName;
    private String itemDescription;
    private Integer quantity;
    private String category;
    // properties is optional metadata; sent as JSON string by frontend if present
    private String properties;
    private Boolean isEquipped;
}
