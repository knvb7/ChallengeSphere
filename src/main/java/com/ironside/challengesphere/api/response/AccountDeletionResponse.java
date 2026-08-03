package com.ironside.challengesphere.api.response;

public record AccountDeletionResponse(
        boolean success,
        String message,
        int mediaCleanupQueued
) {
}
