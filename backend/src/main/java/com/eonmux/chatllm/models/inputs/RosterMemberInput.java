package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.List;

/** One roster member as reviewed before saving. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("RosterMemberInput")
public class RosterMemberInput {
    private CreateCharacterInput character;
    private List<RosterRelationshipInput> relationships;
}
