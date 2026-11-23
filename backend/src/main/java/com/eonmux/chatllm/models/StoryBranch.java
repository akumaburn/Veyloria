package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonObject;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryBranch {
    private UUID branchId;
    private UUID parentConversationId;
    private UUID branchConversationId;
    private UUID branchPointMessageId;
    private UUID branchPointBookmarkId;
    private String label;
    private String description;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject worldStateSnapshot;
    private String createdAt;
}
