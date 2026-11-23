package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.graphql.Name;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Name("CharacterInventoryItem")
public class CharacterInventory {
    private UUID inventoryId;
    private UUID conversationId;
    private UUID characterId;
    private UUID userId;
    private String itemName;
    private String itemDescription;
    @Builder.Default
    private Integer quantity = 1;
    private String category;
    private String properties;
    @Builder.Default
    private Boolean isEquipped = false;
    private String createdAt;
    private String updatedAt;

    public void addQuantity(int amount) {
        if (quantity == null) quantity = 0;
        quantity += amount;
    }

    public boolean removeQuantity(int amount) {
        if (quantity == null || quantity < amount) return false;
        quantity -= amount;
        return true;
    }
}
