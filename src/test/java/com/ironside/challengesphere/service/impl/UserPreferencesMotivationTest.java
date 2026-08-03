package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.UpdateUserPreferencesRequest;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserPreferencesRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPreferencesMotivationTest {

    @Test
    void dedicatedMotivationPreferenceCanBeUpdatedIndependently() throws Exception {
        UserPreferencesRepository preferencesRepository = mock(UserPreferencesRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        UserPreferencesServiceImpl service = new UserPreferencesServiceImpl();
        ReflectionTestUtils.setField(service, "preferencesRepository", preferencesRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);

        UserPreferences saved = new UserPreferences();
        saved.setUserId(7L);
        saved.setNotifyOnMotivation(false);
        when(userRepository.existsById(7L)).thenReturn(true);
        when(preferencesRepository.existsByUserId(7L)).thenReturn(true);
        when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.of(saved));

        UpdateUserPreferencesRequest request = new UpdateUserPreferencesRequest();
        request.setNotifyOnMotivation(false);
        UserPreferences result = service.updatePreferences(7L, request);

        assertFalse(result.getNotifyOnMotivation());
        verify(preferencesRepository).updateNotifyOnMotivation(7L, false);
    }

    @Test
    void missingPreferenceRowInheritsDisabledLegacyGlobalPush() throws Exception {
        UserPreferencesRepository preferencesRepository = mock(UserPreferencesRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        UserPreferencesServiceImpl service = new UserPreferencesServiceImpl();
        ReflectionTestUtils.setField(service, "preferencesRepository", preferencesRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);

        User user = new User();
        user.setId(7L);
        user.setNotifications(0);
        when(userRepository.existsById(7L)).thenReturn(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(preferencesRepository.save(any(UserPreferences.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserPreferences result = service.getPreferences(7L);

        assertFalse(result.getNotifyOnMotivation());
        verify(preferencesRepository).save(result);
    }
}
