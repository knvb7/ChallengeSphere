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
    User getCurrentUser() throws ChallengeSphereException;
}
