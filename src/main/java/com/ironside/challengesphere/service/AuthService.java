package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.response.AuthResponse;
import com.ironside.challengesphere.model.User;

public interface AuthService {
    
    /**
     * Authenticates user using username and password
     * 
     * @param username username for login
     * @param password raw password
     * @return AuthResponse with JWT tokens
     */
    AuthResponse authenticateUser(String username, String password);
    
    /**
     * Refreshes the JWT token using a refresh token
     * 
     * @param refreshToken refresh token previously issued
     * @return AuthResponse with new JWT tokens
     */
    AuthResponse refreshToken(String refreshToken);
    
    /**
     * Gets the currently authenticated user
     * 
     * @return currently authenticated User
     */
    User getCurrentUser();
}
