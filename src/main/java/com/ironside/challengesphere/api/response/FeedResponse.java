package com.ironside.challengesphere.api.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for feed API containing paginated feed items
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class FeedResponse extends CursorResponse {

    private List<FeedItemResponse> items;
    private String feedType;

    /**
     * Create a FeedResponse with pagination info
     */
    public static FeedResponse create(
            List<FeedItemResponse> items,
            String nextCursor,
            boolean hasMore,
            String feedType) {
        FeedResponse response = FeedResponse.builder()
                .items(items)
                .feedType(feedType)
                .build();
        response.setNextCursor(nextCursor);
        response.setHasMore(hasMore);
        return response;
    }

    /**
     * Create an empty feed response
     */
    public static FeedResponse empty(String feedType) {
        FeedResponse response = FeedResponse.builder()
                .items(List.of())
                .feedType(feedType)
                .build();
        response.setHasMore(false);
        return response;
    }
}
