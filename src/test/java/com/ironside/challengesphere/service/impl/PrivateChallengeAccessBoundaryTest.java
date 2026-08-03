package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateChallengeAccessBoundaryTest {

    private static final long CHALLENGE_ID = 31L;
    private static final long SUBMISSION_ID = 71L;
    private static final long AUTHOR_ID = 7L;
    private static final long MEMBER_ID = 8L;
    private static final long OUTSIDER_ID = 9L;

    private ChallengeRepository challengeRepository;
    private SubmissionRepository submissionRepository;
    private UserChallengeRepository userChallengeRepository;
    private UserBlockRepository userBlockRepository;
    private UserRepository userRepository;
    private GloryRepository gloryRepository;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        userRepository = mock(UserRepository.class);
        gloryRepository = mock(GloryRepository.class);

        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(privateChallenge()));
        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(privateSubmission()));
    }

    @Test
    void privateChallengeDetailsAreLimitedToActiveNonBannedMembers() throws Exception {
        ChallengeServiceImpl service = new ChallengeServiceImpl();
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);

        assertForbidden(() -> service.getChallengeDetail(CHALLENGE_ID, OUTSIDER_ID));

        when(userChallengeRepository.findByUserIdAndChallengeId(OUTSIDER_ID, CHALLENGE_ID))
                .thenReturn(Optional.of(membership(OUTSIDER_ID, true, true)));
        assertForbidden(() -> service.getChallengeDetail(CHALLENGE_ID, OUTSIDER_ID));

        when(userChallengeRepository.findByUserIdAndChallengeId(MEMBER_ID, CHALLENGE_ID))
                .thenReturn(Optional.of(membership(MEMBER_ID, true, false)));
        assertNotNull(service.getChallengeDetail(CHALLENGE_ID, MEMBER_ID));
    }

    @Test
    void privateSubmissionBodyAndMediaAreLimitedToActiveNonBannedMembers() throws Exception {
        SubmissionServiceImpl service = new SubmissionServiceImpl();
        ReflectionTestUtils.setField(service, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(service, "userBlockRepository", userBlockRepository);
        ReflectionTestUtils.setField(service, "submissionMediaKeyPolicy", new SubmissionMediaKeyPolicy());
        ReflectionTestUtils.setField(service, "mediaUrlService", mock(MediaUrlService.class));

        assertForbidden(() -> service.getSubmissionById(SUBMISSION_ID, OUTSIDER_ID));

        when(userChallengeRepository.findByUserIdAndChallengeId(OUTSIDER_ID, CHALLENGE_ID))
                .thenReturn(Optional.of(membership(OUTSIDER_ID, true, true)));
        assertForbidden(() -> service.getSubmissionById(SUBMISSION_ID, OUTSIDER_ID));

        when(userChallengeRepository.findByUserIdAndChallengeId(MEMBER_ID, CHALLENGE_ID))
                .thenReturn(Optional.of(membership(MEMBER_ID, true, false)));
        assertNotNull(service.getSubmissionById(SUBMISSION_ID, MEMBER_ID));
    }

    @Test
    void privateSubmissionInteractionMetadataUsesTheSameMembershipBoundary() throws Exception {
        GloryServiceImpl service = new GloryServiceImpl();
        ReflectionTestUtils.setField(service, "gloryRepository", gloryRepository);
        ReflectionTestUtils.setField(service, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(service, "userBlockRepository", userBlockRepository);

        assertForbidden(() -> service.getGloryCount(SUBMISSION_ID, OUTSIDER_ID));
        verify(gloryRepository, never()).countActiveBySubmissionId(SUBMISSION_ID);

        when(userChallengeRepository.findByUserIdAndChallengeId(MEMBER_ID, CHALLENGE_ID))
                .thenReturn(Optional.of(membership(MEMBER_ID, true, false)));
        when(gloryRepository.countActiveBySubmissionId(SUBMISSION_ID)).thenReturn(4L);

        assertEquals(4L, service.getGloryCount(SUBMISSION_ID, MEMBER_ID));
        verify(gloryRepository).countActiveBySubmissionId(SUBMISSION_ID);
    }

    private void assertForbidden(ThrowingCall call) {
        ChallengeSphereException error = assertThrows(ChallengeSphereException.class, call::run);
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    private Challenge privateChallenge() {
        Challenge challenge = new Challenge();
        challenge.setId(CHALLENGE_ID);
        challenge.setTitle("Private crew");
        challenge.setDescription("Members only");
        challenge.setCreatorId(AUTHOR_ID);
        challenge.setCurrentOwnerId(AUTHOR_ID);
        challenge.setNumberOfDays(7);
        challenge.setActive(true);
        challenge.setIsPrivate(true);
        return challenge;
    }

    private Submission privateSubmission() {
        Submission submission = new Submission();
        submission.setId(SUBMISSION_ID);
        submission.setChallengeId(CHALLENGE_ID);
        submission.setUserId(AUTHOR_ID);
        submission.setDayNumber(1);
        submission.setDescription("Private proof");
        submission.setActive(true);
        submission.setIsPrivate(true);
        return submission;
    }

    private UserChallenge membership(long userId, boolean active, boolean banned) {
        UserChallenge membership = new UserChallenge();
        membership.setUserId(userId);
        membership.setChallengeId(CHALLENGE_ID);
        membership.setActive(active);
        membership.setBanned(banned);
        return membership;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
