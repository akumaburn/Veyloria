package com.eonmux.chatllm.models;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A roster generated together. The group carries the shared setting and the
 * ordered member ids; characters remain independent records.
 */
@Data
public class CharacterGroup {
    private UUID id;
    private UUID ownerId;
    private String name;
    private String description;
    private String setting;
    private List<String> characterIds = new ArrayList<>();
    private Integer characterCount = 0;
    private String createdAt;
    private String updatedAt;
}
