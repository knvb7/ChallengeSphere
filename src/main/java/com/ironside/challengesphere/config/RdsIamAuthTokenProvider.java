package com.ironside.challengesphere.config;

@FunctionalInterface
public interface RdsIamAuthTokenProvider {
    String generateToken(String username);
}
