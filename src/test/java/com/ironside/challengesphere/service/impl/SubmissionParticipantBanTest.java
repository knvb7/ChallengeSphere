package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionParticipantBanTest {

    private static final String OWNED_KEY =
            "submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";

    private SubmissionServiceImpl service;
    private SubmissionRepository submissionRepository;
    private ChallengeRepository challengeRepository;
    private UserRepository userRepository;
    private UserChallengeRepository userChallengeRepository;

    @BeforeEach
    void setUp() {
        service = new SubmissionServiceImpl();
        submissionRepository = mock(SubmissionRepository.class);
        challengeRepository = mock(ChallengeRepository.class);
        userRepository = mock(UserRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);
        ReflectionTestUtils.setField(service, "submissionMediaKeyPolicy", new SubmissionMediaKeyPolicy());
        ReflectionTestUtils.setField(service, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
    }

    @Test
    void bannedParticipantCannotCreateSubmission() {
        User user = new User();
        user.setId(7L);
        user.setActive(true);
        UserChallenge membership = bannedMembership();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(challengeRepository.findById(3L)).thenReturn(Optional.of(challenge()));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 3L))
                .thenReturn(Optional.of(membership));

        CreateSubmissionRequest request = request();
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createSubmission(request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals(
                "You were removed from this challenge by its creator.",
                error.getResponseMessage());
        verify(submissionRepository, never()).save(any(Submission.class));
    }

    @Test
    void bannedParticipantCannotReuploadSubmission() {
        Submission submission = new Submission();
        submission.setId(99L);
        submission.setUserId(7L);
        submission.setChallengeId(3L);
        submission.setDayNumber(1);
        submission.setActive(true);
        when(submissionRepository.findById(99L)).thenReturn(Optional.of(submission));
        when(challengeRepository.findById(3L)).thenReturn(Optional.of(challenge()));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 3L))
                .thenReturn(Optional.of(bannedMembership()));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.reuploadSubmission(99L, request()));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals(
                "You were removed from this challenge by its creator.",
                error.getResponseMessage());
        verify(submissionRepository, never()).save(any(Submission.class));
    }

    private CreateSubmissionRequest request() {
        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(7L);
        request.setChallengeId(3L);
        request.setDayNumber(1);
        request.setDescription("proof");
        request.setMediaUrls(List.of(OWNED_KEY));
        return request;
    }

    private Challenge challenge() {
        Challenge challenge = new Challenge();
        challenge.setId(3L);
        challenge.setActive(true);
        challenge.setStartDate(LocalDate.now());
        challenge.setNumberOfDays(30);
        challenge.setChallengeMode(Challenge.MODE_DAILY);
        return challenge;
    }

    private UserChallenge bannedMembership() {
        UserChallenge membership = new UserChallenge();
        membership.setUserId(7L);
        membership.setChallengeId(3L);
        membership.setActive(false);
        membership.setBanned(true);
        return membership;
    }
}
