package com.ironside.challengesphere.security;

import java.time.Duration;

public interface RateLimitStore {

    Decision consume(String key, int maxRequests, Duration window);

    record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }
}
