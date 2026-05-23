package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.GetUserPayload;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private RedisCacheServiceImpl cacheService;

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private MediaUrlService mediaUrlService;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "userRepository", userRepository);
        ReflectionTestUtils.setField(userService, "followRepository", followRepository);
        ReflectionTestUtils.setField(userService, "cacheService", cacheService);
        ReflectionTestUtils.setField(userService, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(userService, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(userService, "userBlockRepository", userBlockRepository);
        ReflectionTestUtils.setField(userService, "mediaUrlService", mediaUrlService);
    }

    @Test
    void getUserReturnsReadableProfilePictureUrl() throws Exception {
        String storedProfilePicUrl = "https://storage.googleapis.com/challengesphere-media/submissions/user_7/PROFILE_PHOTO/avatar.jpg";
        String readableProfilePicUrl = "https://signed.example/avatar.jpg";
        User user = new User();
        user.setId(7L);
        user.setUsername("profileuser");
        user.setEmailId("profile@example.com");
        user.setProfilePicUrl(storedProfilePicUrl);

        when(cacheService.getCachedUserProfile(eq(7L), eq(User.class))).thenReturn(Optional.empty());
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(submissionRepository.countActiveByUserId(7L)).thenReturn(0);
        when(mediaUrlService.toReadableMediaUrl(storedProfilePicUrl)).thenReturn(readableProfilePicUrl);

        GetUserPayload payload = new GetUserPayload();
        payload.setUserId(7L);
        payload.setCurrentUserId(7L);

        User response = userService.getUser(payload);

        assertEquals(readableProfilePicUrl, response.getProfilePicUrl());
        verify(mediaUrlService).toReadableMediaUrl(storedProfilePicUrl);
    }
}
