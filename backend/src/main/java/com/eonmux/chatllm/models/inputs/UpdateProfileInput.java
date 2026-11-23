package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("UpdateProfileInput")
public class UpdateProfileInput {
    private String displayName;
    private String email;
    private String bio;
    private String profilePicture;
    private String username;
}
