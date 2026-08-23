package com.ironside.challengesphere.service;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.api.response.GloryUserResponse;
import com.ironside.challengesphere.model.Glory;

import java.util.List;

public interface GloryService {
    
    /**
     * Toggle glory for a submission by a user
     * @return true if glory is given, false if removed
     */
    boolean toggleGlory(String submissionId, String userId) throws ChallengeSphereException;
    
    /**
     * Get all active glories for a submission
     */
    List<Glory> getGloriesForSubmission(String submissionId) throws ChallengeSphereException;

    /**
     * Paginated variant — intended for public/UI consumption. Unbounded lists of
     * glories scale with each submission's popularity (easily 100k+ on a viral post).
     */
    CursorPageResponse<GloryUserResponse> getGloriesForSubmission(
            String submissionId,
            String callerId,
            int limit,
            String cursor
    ) throws ChallengeSphereException;
    
    /**
     * Check if a user has given glory to a submission
     */
    boolean hasUserGivenGlory(String submissionId, String userId) throws ChallengeSphereException;
    
    /**
     * Get glory count for a submission
     */
    Long getGloryCount(String submissionId, String viewerUserId) throws ChallengeSphereException;

    /**
     * Toggle unglory (not genuine) for a submission.
     * @return true if ungloried, false if unglory was removed
     */
    boolean toggleUnglory(String submissionId, String userId) throws ChallengeSphereException;

    /**
     * Check if a user has ungloried a submission
     */
    boolean hasUserUngloried(String submissionId, String userId) throws ChallengeSphereException;
}
