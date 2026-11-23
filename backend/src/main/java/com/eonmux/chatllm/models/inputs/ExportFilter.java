package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("ExportFilter")
public class ExportFilter {
    private Boolean conversations;
    private Boolean messages;
    private Boolean characters;
    private Boolean settings;
}
