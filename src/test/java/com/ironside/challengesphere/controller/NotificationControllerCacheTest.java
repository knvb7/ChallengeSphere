package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.impl.RedisCacheServiceImpl;
import com.ironside.challengesphere.util.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationControllerCacheTest {

    @Test
    void cachesUnreadCountForSixtySecondsAndInvalidatesAfterReading() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        RedisCacheServiceImpl cache = mock(RedisCacheServiceImpl.class);
        NotificationController controller = new NotificationController();
        ReflectionTestUtils.setField(controller, "notificationRepository", repository);
        ReflectionTestUtils.setField(controller, "cacheService", cache);
        UserPrincipal principal = new UserPrincipal(42L, "user", "", true, UserRole.USER);

        when(repository.countUnreadNotificationsByUserId(42L)).thenReturn(3L);

        assertEquals(3L, controller.getUnreadCount(42L, principal).getBody().get("unreadCount"));
        verify(cache).save("notifications:unread:42", 3L, Duration.ofSeconds(60));

        controller.markRead(42L, 9L, principal);
        verify(cache).delete("notifications:unread:42");
    }
}
