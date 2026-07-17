package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.GoogleAuthRequest;
import com.ironside.challengesphere.api.request.LoginRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.AuthResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;

public interface AuthService {
    AuthResponse register(UserRegistrationRequest request) throws ChallengeSphereException;
    AuthResponse login(LoginRequest request) throws ChallengeSphereException;
    AuthResponse refresh(String refreshToken) throws ChallengeSphereException;
    AuthResponse loginWithGoogle(GoogleAuthRequest request) throws ChallengeSphereException;
    void logout(String accessToken, String refreshToken);
    User getCurrentUser(String accessToken) throws ChallengeSphereException;
    User getCurrentUser() throws ChallengeSphereException;
    void changePassword(Long userId, String currentPassword, String newPassword, String confirmPassword) throws ChallengeSphereException;
    void resetPassword(String token, String newPassword, String confirmPassword) throws ChallengeSphereException;
}
