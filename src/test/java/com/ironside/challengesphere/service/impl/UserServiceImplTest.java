package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.UpdateUserRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RedisCacheServiceImpl cacheService;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProfilePhotoService profilePhotoService;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "userRepository", userRepository);
        ReflectionTestUtils.setField(userService, "cacheService", cacheService);
        ReflectionTestUtils.setField(userService, "profilePhotoService", profilePhotoService);
        ReflectionTestUtils.setField(userService, "followRepository", followRepository);
        ReflectionTestUtils.setField(userService, "userBlockRepository", userBlockRepository);
        ReflectionTestUtils.setField(userService, "notificationService", notificationService);
    }

    @Test
    void updateUserDetailsRejectsUsernameTakenByAnotherUser() {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setUsername("current");

        UpdateUserRequest request = new UpdateUserRequest();
        request.setUsername("TakenName");

        when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));
        when(userRepository.existsByUsernameIgnoreCaseAndIdNot("TakenName", 1L)).thenReturn(true);

        ChallengeSphereException exception = assertThrows(
                ChallengeSphereException.class,
                () -> userService.updateUserDetails(1L, request)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("Username is already taken", exception.getResponseMessage());
        verify(userRepository, never()).save(currentUser);
    }

    @Test
    void updateUserDetailsRejectsReservedChallengeSphereUsername() {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setUsername("current");

        UpdateUserRequest request = new UpdateUserRequest();
        request.setUsername("myChallengeSphere");

        when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));

        ChallengeSphereException exception = assertThrows(
                ChallengeSphereException.class,
                () -> userService.updateUserDetails(1L, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("Username cannot contain \"challengesphere\"", exception.getResponseMessage());
        verify(userRepository, never()).existsByUsernameIgnoreCaseAndIdNot("myChallengeSphere", 1L);
        verify(userRepository, never()).save(currentUser);
    }

    @Test
    void updateUserDetailsRejectsUsernameOutsideLengthLimit() {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setUsername("current");

        UpdateUserRequest request = new UpdateUserRequest();
        request.setUsername("four");

        when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));

        ChallengeSphereException exception = assertThrows(
                ChallengeSphereException.class,
                () -> userService.updateUserDetails(1L, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("Username must be between 5 and 20 characters", exception.getResponseMessage());
        verify(userRepository, never()).save(currentUser);
    }

    @Test
    void registerUserRejectsReservedChallengeSphereUsername() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setUsername("ChallengeSphereOfficial");
        request.setPassword("StrongPassword123!");

        ChallengeSphereException exception = assertThrows(
                ChallengeSphereException.class,
                () -> userService.registerUser(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("Username cannot contain \"challengesphere\"", exception.getResponseMessage());
        verify(userRepository, never()).findByUsername("ChallengeSphereOfficial");
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void updateUserDetailsAllowsCurrentUsername() throws Exception {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setUsername("current");

        UpdateUserRequest request = new UpdateUserRequest();
        request.setUsername("current");

        when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));
        when(userRepository.save(currentUser)).thenReturn(currentUser);

        User updatedUser = userService.updateUserDetails(1L, request);

        assertEquals("current", updatedUser.getUsername());
        verify(userRepository, never()).existsByUsernameIgnoreCaseAndIdNot("current", 1L);
        verify(userRepository).save(currentUser);
    }

    @Test
    void updateUserDetailsPersistsTrimmedCountry() throws Exception {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setUsername("current");

        UpdateUserRequest request = new UpdateUserRequest();
        request.setCountry("  India  ");

        when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));
        when(userRepository.save(currentUser)).thenReturn(currentUser);

        User updatedUser = userService.updateUserDetails(1L, request);

        assertEquals("India", updatedUser.getCountry());
        verify(userRepository).save(currentUser);
    }

    @Test
    void updateUserDetailsReturnsConflictWhenDatabaseRejectsDuplicateUsernameRace() {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setUsername("current");

        UpdateUserRequest request = new UpdateUserRequest();
        request.setUsername("newname");

        when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));
        when(userRepository.existsByUsernameIgnoreCaseAndIdNot("newname", 1L)).thenReturn(false);
        when(userRepository.save(currentUser)).thenThrow(new DataIntegrityViolationException("duplicate username"));

        ChallengeSphereException exception = assertThrows(
                ChallengeSphereException.class,
                () -> userService.updateUserDetails(1L, request)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("Username is already taken", exception.getResponseMessage());
    }

    @Test
    void followSchedulesNotificationOnlyAfterOuterTransactionCommit() throws Exception {
        User follower = new User();
        follower.setId(1L);
        follower.setUsername("follower");
        User followed = new User();
        followed.setId(2L);
        followed.setUsername("followed");

        when(userRepository.findById(1L)).thenReturn(Optional.of(follower));
        when(userRepository.findById(2L)).thenReturn(Optional.of(followed));
        when(followRepository.findByFollowerIdAndFollowingId(1L, 2L)).thenReturn(Optional.empty());
        when(followRepository.save(org.mockito.ArgumentMatchers.any(Follow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            userService.follow(1L, 2L);

            verify(notificationService, never()).notifyFollowAsync(2L, 1L);
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(notificationService).notifyFollowAsync(2L, 1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
