package com.ironside.challengesphere.api.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Shared response contract for opaque cursor pagination. */
@Getter
@Setter
@NoArgsConstructor
public abstract class CursorResponse {

    private String nextCursor;
    private boolean hasMore;

    protected CursorResponse(String nextCursor, boolean hasMore) {
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }
}
