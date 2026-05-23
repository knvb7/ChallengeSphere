package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.ChallengeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ChallengeService challengeService;

    private NotificationController controller;

    @BeforeEach
    void setUp() {
        controller = new NotificationController();
        ReflectionTestUtils.setField(controller, "notificationRepository", notificationRepository);
        ReflectionTestUtils.setField(controller, "challengeService", challengeService);
    }

    @Test
    void acceptChallengeInviteJoinsChallengeAndMarksNotificationRead() throws Exception {
        Notification notification = inviteNotification(99L, 2L, "{\"challengeId\":10}");
        UserPrincipal principal = new UserPrincipal(2L, "invitee", "password", true);

        when(notificationRepository.findById(99L)).thenReturn(Optional.of(notification));
        when(challengeService.joinChallenge(argThat(request ->
                request.getChallengeId().equals(10L) && request.getUserIds().equals(List.of(2L))
        ))).thenReturn(JoinChallengeResponse.success(10L, "Morning Run", List.of(2L)));

        ResponseEntity<JoinChallengeResponse> response = controller.acceptChallengeInvite(99L, principal);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(2L), response.getBody().getSuccessfulUserIds());
        verify(notificationRepository).markAsRead(2L, 99L);
    }

    @Test
    void ignoreChallengeInviteMarksNotificationRead() throws Exception {
        Notification notification = inviteNotification(99L, 2L, "{\"challengeId\":10}");
        UserPrincipal principal = new UserPrincipal(2L, "invitee", "password", true);

        when(notificationRepository.findById(99L)).thenReturn(Optional.of(notification));

        ResponseEntity<Map<String, Object>> response = controller.ignoreChallengeInvite(99L, principal);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("handled"));
        assertEquals(99L, response.getBody().get("notificationId"));
        verify(notificationRepository).markAsRead(2L, 99L);
    }

    private Notification inviteNotification(Long id, Long userId, String actionObject) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setUserId(userId);
        notification.setType("CHALLENGE_INVITE");
        notification.setMessage("Join Morning Run");
        notification.setActionObject(actionObject);
        return notification;
    }
}
