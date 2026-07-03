package com.ironside.challengesphere.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class RedisRateLimitStore implements RateLimitStore {

    private static final DefaultRedisScript<List> CONSUME_SCRIPT;

    static {
        CONSUME_SCRIPT = new DefaultRedisScript<>();
        CONSUME_SCRIPT.setResultType(List.class);
        CONSUME_SCRIPT.setScriptText("""
                local current = redis.call('INCR', KEYS[1])
                local ttl = redis.call('PTTL', KEYS[1])
                if current == 1 or ttl < 0 then
                    redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    ttl = tonumber(ARGV[1])
                end
                return { current, ttl }
                """);
    }

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Decision consume(String key, int maxRequests, Duration window) {
        if (key == null || key.isBlank() || maxRequests < 1 || window == null
                || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit configuration is invalid");
        }

        List<?> result = redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of(key),
                Long.toString(window.toMillis())
        );
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("Distributed rate-limit store returned no result");
        }

        long count = ((Number) result.get(0)).longValue();
        long ttlMillis = Math.max(0, ((Number) result.get(1)).longValue());
        int remaining = (int) Math.max(0, maxRequests - count);
        long retryAfterSeconds = Math.max(1, (ttlMillis + 999) / 1000);
        return new Decision(count <= maxRequests, remaining, retryAfterSeconds);
    }
}
