package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("CharacterFilter")
public class CharacterFilter {
    private Boolean isPublic;
    private Boolean isFavorite;
    private String category;
    private List<String> tags;
    private String search;
}
