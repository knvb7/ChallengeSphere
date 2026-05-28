package com.ironside.challengesphere.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void getStopsRetryingRedisDuringFailureCooldown() {
        RedisCacheServiceImpl service = new RedisCacheServiceImpl();
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);

        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        assertNull(service.get("feed:first", String.class));
        assertNull(service.get("feed:second", String.class));

        verify(valueOperations, times(1)).get(anyString());
    }
}
