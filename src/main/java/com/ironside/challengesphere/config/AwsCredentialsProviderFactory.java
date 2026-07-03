package com.ironside.challengesphere.config;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;

public final class AwsCredentialsProviderFactory {

    private AwsCredentialsProviderFactory() {
    }

    public static AwsCredentialsProvider create(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return DefaultCredentialsProvider.create();
        }
        return ProfileCredentialsProvider.builder()
                .profileName(profileName)
                .build();
    }
}
