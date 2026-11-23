package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("CreatePartyInput")
public class CreatePartyInput {
    private String name;
    private String description;
    private Integer maxMembers;
    private Boolean isPublic;
}
