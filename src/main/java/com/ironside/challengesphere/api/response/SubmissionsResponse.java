package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Submission;
import lombok.Data;

import java.util.List;

@Data
public class SubmissionsResponse {
    private List<Submission> submissions;
    private int currentPage;
    private int pageSize;
    private int totalCount;
    private int totalPages;

    public static SubmissionsResponse create(List<Submission> submissions, int page, int pageSize, int totalCount) {
        SubmissionsResponse response = new SubmissionsResponse();
        response.setSubmissions(submissions);
        response.setCurrentPage(page);
        response.setPageSize(pageSize);
        response.setTotalCount(totalCount);
        response.setTotalPages((int) Math.ceil((double) totalCount / pageSize));
        return response;
    }
}
