package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplUsernameTest {

    @Test
    void generatedGoogleUsernameUsesTheVerifiedNameAndStaysWithinTheLengthLimit() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        AuthServiceImpl service = serviceWith(userRepository);

        String username = ReflectionTestUtils.invokeMethod(
                service,
                "generateUsername",
                "person@example.com",
                "Google Person With An Extremely Long Name"
        );

        assertTrue(username.startsWith("google_person"));
        assertTrue(username.length() >= 5);
        assertTrue(username.length() <= 20);
    }

    @Test
    void temporaryUsernameRetriesAfterCollision() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername(anyString()))
                .thenReturn(Optional.of(new User()), Optional.empty());
        AuthServiceImpl service = serviceWith(userRepository);

        String username = ReflectionTestUtils.invokeMethod(
                service,
                "generateUsername",
                "person@example.com",
                "Google Person"
        );

        assertTrue(username.startsWith("google_person"));
        verify(userRepository, org.mockito.Mockito.times(2)).findByUsername(anyString());
    }

    @Test
    void newGoogleUsersUseTheVerifiedGoogleAccountName() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthServiceImpl service = serviceWith(userRepository);

        User user = ReflectionTestUtils.invokeMethod(
                service,
                "createGoogleUser",
                "person@example.com",
                "google-id",
                "Google Person",
                "https://example.com/avatar.jpg"
        );

        assertEquals("Google Person", user.getName());
        assertEquals("google_person", user.getUsername());
        assertEquals("https://example.com/avatar.jpg", user.getProfilePicUrl());
    }

    @Test
    void googleNameBackfillsAnEmptyNameWithoutReplacingACustomName() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthServiceImpl service = serviceWith(userRepository);

        User unnamedUser = new User();
        User backfilled = ReflectionTestUtils.invokeMethod(
                service,
                "backfillGoogleName",
                unnamedUser,
                "  Google   Person  "
        );
        assertEquals("Google Person", backfilled.getName());

        User customizedUser = new User();
        customizedUser.setName("Chosen Name");
        User preserved = ReflectionTestUtils.invokeMethod(
                service,
                "backfillGoogleName",
                customizedUser,
                "Google Person"
        );
        assertEquals("Chosen Name", preserved.getName());
    }

    private static AuthServiceImpl serviceWith(UserRepository userRepository) {
        return new AuthServiceImpl(userRepository, null, null, null, null, null, null);
    }
}
