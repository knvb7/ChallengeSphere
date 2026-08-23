package com.ironside.challengesphere.api.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Generic cursor page used by list endpoints without extra domain metadata. */
@Getter
@Setter
@NoArgsConstructor
public class CursorPageResponse<T> extends CursorResponse {

    private List<T> items = List.of();

    public CursorPageResponse(List<T> items, String nextCursor, boolean hasMore) {
        super(nextCursor, hasMore);
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> CursorPageResponse<T> of(
            List<T> items,
            String nextCursor,
            boolean hasMore
    ) {
        return new CursorPageResponse<>(items, nextCursor, hasMore);
    }

    public static <T> CursorPageResponse<T> empty() {
        return new CursorPageResponse<>(List.of(), null, false);
    }
}
