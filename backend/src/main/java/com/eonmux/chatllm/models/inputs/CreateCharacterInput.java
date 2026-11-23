package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("CreateCharacterInput")
public class CreateCharacterInput {
    private String name;
    private String description;
    private String systemPrompt;
    private String appearance;
    private String personality;
    private String gender;
    private String pronouns;
    private Integer ageYears;
    private String height;
    private String build;
    private String hair;
    private String eyes;
    private Integer strength;
    private Integer dexterity;
    private Integer constitution;
    private Integer intelligence;
    private Integer wisdom;
    private Integer charisma;
    private String likes;
    private String dislikes;
    private String backstory;
    private String goals;
    private String greeting;
    private String initialPrompt;
    private String examples;
    private String avatar;
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    private Integer topK;
    private Double repeatPenalty;
    private Boolean isPublic;
    private String category;
    private List<String> tags;
    private Integer initialAffinityToUser;
    private Integer initialAffinityFromUser;
    /** Starting alignment points written to the alignment table on create. */
    private Integer lawfulness;
    private Integer goodness;
}
