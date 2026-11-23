package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("UpdateParticipantInput")
public class UpdateParticipantInput {
    private String conversationId;
    private String userId;
    private String role;
    private Boolean canInvite;
    private Boolean canAddCharacters;
    private String nickname;
}
