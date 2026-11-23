package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityTemplatePagination {
    private List<CommunityTemplate> items;
    private long totalCount;
    private boolean hasMore;
}
