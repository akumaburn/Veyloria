package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("CreateBranchInput")
public class CreateBranchInput {
    private String parentConversationId;
    private String branchPointMessageId;
    private String bookmarkId;
    private String label;
    private String description;
}
