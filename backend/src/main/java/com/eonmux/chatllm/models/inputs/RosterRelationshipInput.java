package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

/** A saved feeling from the member being created toward another roster member. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("RosterRelationshipInput")
public class RosterRelationshipInput {
    private String targetName;
    private String tier;
    private String reason;
}
