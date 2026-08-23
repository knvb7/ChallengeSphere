package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Submission;
import lombok.EqualsAndHashCode;
import lombok.Data;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class SubmissionsResponse extends CursorResponse {
    private List<Submission> submissions;

    public static SubmissionsResponse create(
            List<Submission> submissions,
            String nextCursor,
            boolean hasMore
    ) {
        SubmissionsResponse response = new SubmissionsResponse();
        response.setSubmissions(submissions);
        response.setNextCursor(nextCursor);
        response.setHasMore(hasMore);
        return response;
    }
}
