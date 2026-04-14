package com.ironside.challengesphere.service;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Glory;

import java.util.List;

public interface GloryService {
    
    /**
     * Toggle glory for a submission by a user
     * @return true if glory is given, false if removed
     */
    boolean toggleGlory(Long submissionId, Long userId) throws ChallengeSphereException;
    
    /**
     * Get all active glories for a submission
     */
    List<Glory> getGloriesForSubmission(Long submissionId) throws ChallengeSphereException;
    
    /**
     * Check if a user has given glory to a submission
     */
    boolean hasUserGivenGlory(Long submissionId, Long userId) throws ChallengeSphereException;
    
    /**
     * Get glory count for a submission
     */
    Long getGloryCount(Long submissionId) throws ChallengeSphereException;

    /**
     * Toggle unglory (not genuine) for a submission.
     * @return true if ungloried, false if unglory was removed
     */
    boolean toggleUnglory(Long submissionId, Long userId) throws ChallengeSphereException;

    /**
     * Check if a user has ungloried a submission
     */
    boolean hasUserUngloried(Long submissionId, Long userId) throws ChallengeSphereException;
}
