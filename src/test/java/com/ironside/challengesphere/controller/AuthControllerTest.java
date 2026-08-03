package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.response.SelfUserResponse;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.AuthService;
import com.ironside.challengesphere.service.SmsService;
import com.ironside.challengesphere.service.UserService;
import com.ironside.challengesphere.service.impl.RedisCacheServiceImpl;
import com.ironside.challengesphere.util.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void meUsesReadableUserFromAuthenticatedService() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserService userService = mock(UserService.class);
        AuthController controller = new AuthController(
                mock(SmsService.class),
                userService,
                mock(UserRepository.class),
                authService,
                mock(RedisCacheServiceImpl.class));
        UserPrincipal principal = new UserPrincipal(2L, "first_user", null, true, UserRole.USER);
        User readableUser = new User();
        readableUser.setId(2L);
        readableUser.setUsername("first_user");
        readableUser.setProfilePicUrl("https://signed.example/profile.jpg");
        when(authService.getCurrentUser("access-token")).thenReturn(readableUser);

        ResponseEntity<SelfUserResponse> response = controller.me(principal, "Bearer access-token");

        assertEquals("https://signed.example/profile.jpg", response.getBody().getProfilePicUrl());
        verify(authService).getCurrentUser("access-token");
    }
}
