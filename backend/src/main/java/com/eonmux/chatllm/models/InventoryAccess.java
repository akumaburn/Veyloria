package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAccess {
    private Boolean canModify;
    private Boolean canManage;
    private List<Recipient> recipients;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recipient {
        private String id;
        private String name;
        private String kind;
        private Boolean canReceiveEffects;
    }
}
