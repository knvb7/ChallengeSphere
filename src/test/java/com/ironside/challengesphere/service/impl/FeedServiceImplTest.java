package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.FeedResponse;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedServiceImplTest {

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChallengeRepository challengeRepository;

    @Mock
    private GloryRepository gloryRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private RedisCacheServiceImpl cacheService;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private MediaUrlService mediaUrlService;

    private FeedServiceImpl feedService;

    @BeforeEach
    void setUp() {
        feedService = new FeedServiceImpl();
        ReflectionTestUtils.setField(feedService, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(feedService, "userRepository", userRepository);
        ReflectionTestUtils.setField(feedService, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(feedService, "gloryRepository", gloryRepository);
        ReflectionTestUtils.setField(feedService, "followRepository", followRepository);
        ReflectionTestUtils.setField(feedService, "cacheService", cacheService);
        ReflectionTestUtils.setField(feedService, "userBlockRepository", userBlockRepository);
        ReflectionTestUtils.setField(feedService, "mediaUrlService", mediaUrlService);
    }

    @Test
    void getExploreFeedDoesNotReturnSubmissionsFromInactiveChallenges() throws Exception {
        Submission activeChallengePost = submission(100L, 2L, 10L, "active-proof.jpg");
        Submission deletedChallengePost = submission(101L, 3L, 11L, "deleted-proof.jpg");

        when(cacheService.getCachedFeed(eq("EXPLORE:READABLE_AVATARS_V3"), eq(1L), eq(1), eq(20), eq(FeedResponse.class)))
                .thenReturn(Optional.empty());
        when(submissionRepository.findPublicSubmissions(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(activeChallengePost, deletedChallengePost),
                        PageRequest.of(0, 20),
                        2
                ));
        when(userRepository.findAllById(any()))
                .thenReturn(List.of(user(2L, "active_user"), user(3L, "deleted_user")));
        when(challengeRepository.findAllById(any()))
                .thenReturn(List.of(challenge(10L, "Active Challenge", true), challenge(11L, "Deleted Challenge", false)));

        FeedResponse response = feedService.getExploreFeed(1, 20, 1L);

        assertEquals(List.of(100L), response.getItems().stream()
                .map(item -> item.getSubmissionId())
                .toList());
    }

    @Test
    void getExploreFeedReturnsReadableProfilePictureUrls() throws Exception {
        String storedProfilePicUrl = "submissions/user_2/PROFILE_PHOTO/avatar.jpg";
        String readableProfilePicUrl = "https://signed.example.com/avatar.jpg";
        Submission post = submission(100L, 2L, 10L, "active-proof.jpg");
        User author = user(2L, "active_user");
        author.setProfilePicUrl(storedProfilePicUrl);

        when(cacheService.getCachedFeed(eq("EXPLORE:READABLE_AVATARS_V3"), eq(1L), eq(1), eq(20), eq(FeedResponse.class)))
                .thenReturn(Optional.empty());
        when(submissionRepository.findPublicSubmissions(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(post), PageRequest.of(0, 20), 1));
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(challengeRepository.findAllById(any())).thenReturn(List.of(challenge(10L, "Active Challenge", true)));
        when(mediaUrlService.toReadableMediaUrl(storedProfilePicUrl)).thenReturn(readableProfilePicUrl);

        FeedResponse response = feedService.getExploreFeed(1, 20, 1L);

        assertEquals(readableProfilePicUrl, response.getItems().get(0).getUserProfilePicUrl());
    }

    private Submission submission(Long id, Long userId, Long challengeId, String mediaUrls) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setUserId(userId);
        submission.setChallengeId(challengeId);
        submission.setDayNumber(1);
        submission.setDescription("Daily proof");
        submission.setMediaUrls(mediaUrls);
        submission.setIsPrivate(false);
        submission.setSubmissionTime(new Timestamp(System.currentTimeMillis()));
        submission.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        submission.setActive(true);
        submission.setGloriesCount(0L);
        submission.setCommentsCount(0L);
        return submission;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmailId(username + "@example.com");
        user.setActive(true);
        return user;
    }

    private Challenge challenge(Long id, String title, boolean active) {
        Challenge challenge = new Challenge();
        challenge.setId(id);
        challenge.setTitle(title);
        challenge.setDescription("Challenge description");
        challenge.setCreatorId(2L);
        challenge.setCurrentOwnerId(2L);
        challenge.setStartDate(LocalDate.now());
        challenge.setNumberOfDays(7);
        challenge.setIsPrivate(false);
        challenge.setActive(active);
        challenge.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        return challenge;
    }
}
