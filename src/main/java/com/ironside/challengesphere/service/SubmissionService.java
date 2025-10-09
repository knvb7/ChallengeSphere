//package com.ironside.challengesphere.service;
//
//import com.ironside.challengesphere.api.request.SubmissionRequest;
//
//import java.time.LocalDate;
//import java.util.List;
//
//public interface SubmissionService {
//
//    /**
//     * Submits a daily proof for a challenge
//     *
//     * @param userId ID of the submitting user
//     * @param challengeId ID of the challenge
//     * @param submission submission details
//     * @return the submitted proof
//     */
//    SubmissionResponse submitDailyProof(Long userId, Long challengeId, SubmissionRequest submission);
//
//    /**
//     * Gets all submissions for a challenge by a specific user
//     *
//     * @param userId ID of the user
//     * @param challengeId ID of the challenge
//     * @return list of submissions
//     */
//    List<SubmissionResponse> getUserSubmissionsForChallenge(Long userId, Long challengeId);
//
//    /**
//     * Gets all submissions for a challenge on a specific date
//     *
//     * @param challengeId ID of the challenge
//     * @param date date to filter by
//     * @return list of submissions
//     */
//    List<SubmissionResponse> getSubmissionsForChallengeByDate(Long challengeId, LocalDate date);
//
//    /**
//     * Gets all submissions for a challenge
//     *
//     * @param challengeId ID of the challenge
//     * @return list of submissions
//     */
//    List<SubmissionResponse> getAllSubmissionsForChallenge(Long challengeId);
//
//    /**
//     * Verifies a submission
//     *
//     * @param submissionId ID of the submission
//     * @return the updated submission
//     */
//    SubmissionResponse verifySubmission(Long submissionId);
//
//    /**
//     * Rejects a submission
//     *
//     * @param submissionId ID of the submission
//     */
//    void rejectSubmission(Long submissionId);
//}
