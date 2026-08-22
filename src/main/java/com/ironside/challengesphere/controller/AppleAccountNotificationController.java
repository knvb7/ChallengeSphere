package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.service.impl.AppleAccountNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/apple/notifications")
public class AppleAccountNotificationController {

    private final AppleAccountNotificationService notificationService;

    public AppleAccountNotificationController(AppleAccountNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody Map<String, Object> body)
            throws ChallengeSphereException {
        Object signedPayload = body.containsKey("payload")
                ? body.get("payload")
                : body.get("signedPayload");
        notificationService.process(signedPayload == null ? null : signedPayload.toString());
        return ResponseEntity.ok().build();
    }
}
