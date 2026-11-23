package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("AttachmentInput")
public class AttachmentInput {
    private String filename;
    private String mimeType;
    private Integer size;
    private String url;
}
