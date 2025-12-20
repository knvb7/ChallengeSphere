package com.ironside.challengesphere.api.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for feed API containing paginated feed items
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedResponse {

    private List<FeedItemResponse> items;
    private int page;
    private int pageSize;
    private long totalItems;
    private int totalPages;
    private boolean hasMore;
    private String feedType;

    /**
     * Create a FeedResponse with pagination info
     */
    public static FeedResponse create(
            List<FeedItemResponse> items,
            int page,
            int pageSize,
            long totalItems,
            String feedType) {
        
        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        boolean hasMore = page < totalPages;

        return FeedResponse.builder()
                .items(items)
                .page(page)
                .pageSize(pageSize)
                .totalItems(totalItems)
                .totalPages(totalPages)
                .hasMore(hasMore)
                .feedType(feedType)
                .build();
    }

    /**
     * Create an empty feed response
     */
    public static FeedResponse empty(int page, int pageSize, String feedType) {
        return FeedResponse.builder()
                .items(List.of())
                .page(page)
                .pageSize(pageSize)
                .totalItems(0)
                .totalPages(0)
                .hasMore(false)
                .feedType(feedType)
                .build();
    }
}
