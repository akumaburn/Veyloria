package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("SendMessageInput")
public class SendMessageInput {
    private String conversationId;
    private String content;
    private Boolean isBase64Encoded;
    private List<AttachmentInput> attachments;
}
