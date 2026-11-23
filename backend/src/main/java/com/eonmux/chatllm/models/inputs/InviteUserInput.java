package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("InviteUserInput")
public class InviteUserInput {
    private String conversationId;
    private String usernameOrEmail;
    private String message;
    private String roleToGrant;
}
