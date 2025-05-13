package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.SubmissionRequest;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.UserProfileResponse;
import com.ironside.challengesphere.model.ChallengeParticipant;
import com.ironside.challengesphere.model.DailySubmission;
import com.ironside.challengesphere.repository.ChallengeParticipantRepository;
import com.ironside.challengesphere.repository.DailySubmissionRepository;
import com.ironside.challengesphere.service.SubmissionService;
import com.ironside.challengesphere.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubmissionServiceImpl implements SubmissionService {

    private final DailySubmissionRepository submissionRepository;
    private final ChallengeParticipantRepository participantRepository;
    private final UserService userService;

    @Override
    @Transactional
    public SubmissionResponse submitDailyProof(Long userId, Long challengeId, SubmissionRequest submission) {
        // Find participant record
        ChallengeParticipant participant = participantRepository.findByUserIdAndChallengeId(userId, challengeId)
                .orElseThrow(() -> new EntityNotFoundException("User is not participating in this challenge"));
        
        // Determine the submission date (use provided date or default to today)
        LocalDate submissionDate = submission.getSubmissionDate() != null ? 
                submission.getSubmissionDate() : LocalDate.now();
        
        // Check if already submitted for this date
        Optional<DailySubmission> existingSubmission = 
                submissionRepository.findByParticipantIdAndSubmissionDate(participant.getParticipantId(), submissionDate);
        
        if (existingSubmission.isPresent()) {
            throw new IllegalStateException("Already submitted proof for this date");
        }
        
        // Create a submission record
        DailySubmission dailySubmission = new DailySubmission();
        dailySubmission.setParticipant(participant);
        dailySubmission.setMediaUrl(submission.getMediaUrl());
        dailySubmission.setNotes(submission.getNotes());
        dailySubmission.setSubmissionDate(submissionDate);
        dailySubmission.setSubmittedAt(OffsetDateTime.now());
        dailySubmission.setIsVerified(false);
        
        // Generate verification code if provided
        if (submission.getAuthenticityCode() != null) {
            dailySubmission.setVerificationCode(submission.getAuthenticityCode());
        } else {
            // Generate random code
            dailySubmission.setVerificationCode(UUID.randomUUID().toString().substring(0, 8));
        }
        
        // Save submission
        DailySubmission savedSubmission = submissionRepository.save(dailySubmission);
        
        // Return response
        return mapToSubmissionResponse(savedSubmission);
    }

    @Override
    public List<SubmissionResponse> getUserSubmissionsForChallenge(Long userId, Long challengeId) {
        // Find participant record
        ChallengeParticipant participant = participantRepository.findByUserIdAndChallengeId(userId, challengeId)
                .orElseThrow(() -> new EntityNotFoundException("User is not participating in this challenge"));
        
        // Get all submissions
        List<DailySubmission> submissions = submissionRepository.findByParticipantId(participant.getParticipantId());
        
        return submissions.stream()
                .map(this::mapToSubmissionResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<SubmissionResponse> getSubmissionsForChallengeByDate(Long challengeId, LocalDate date) {
        List<DailySubmission> submissions = submissionRepository.findByParticipantChallengeIdAndSubmissionDate(challengeId, date);
        
        return submissions.stream()
                .map(this::mapToSubmissionResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<SubmissionResponse> getAllSubmissionsForChallenge(Long challengeId) {
        List<DailySubmission> submissions = submissionRepository.findByParticipantChallengeId(challengeId);
        
        return submissions.stream()
                .map(this::mapToSubmissionResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public SubmissionResponse verifySubmission(Long submissionId) {
        DailySubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("Submission not found with id: " + submissionId));
        
        submission.setIsVerified(true);
        DailySubmission updatedSubmission = submissionRepository.save(submission);
        
        return mapToSubmissionResponse(updatedSubmission);
    }

    @Override
    @Transactional
    public void rejectSubmission(Long submissionId) {
        DailySubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("Submission not found with id: " + submissionId));
        
        // Simply delete the submission
        submissionRepository.delete(submission);
    }
    
    /**
     * Maps a DailySubmission entity to a SubmissionResponse
     */
    private SubmissionResponse mapToSubmissionResponse(DailySubmission submission) {
        SubmissionResponse response = new SubmissionResponse();
        
        response.setSubmissionId(submission.getSubmissionId());
        response.setChallengeId(submission.getParticipant().getChallenge().getChallengeId());
        response.setParticipantId(submission.getParticipant().getParticipantId());
        response.setMediaUrl(submission.getMediaUrl());
        response.setNotes(submission.getNotes());
        response.setSubmissionDate(submission.getSubmissionDate());
        response.setSubmittedAt(submission.getSubmittedAt());
        response.setVerified(submission.getIsVerified());
        
        // Get user profile of submitter
        UserProfileResponse submitterProfile = 
                userService.getUserProfile(submission.getParticipant().getUser().getUserId());
        response.setSubmitter(submitterProfile);
        
        return response;
    }
}
