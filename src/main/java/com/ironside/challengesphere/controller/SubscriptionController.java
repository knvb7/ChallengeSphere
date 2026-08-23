package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.response.SubscriptionStatusResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {
    private final SubscriptionService subscriptionService;

    @GetMapping("/me")
    public ResponseEntity<SubscriptionStatusResponse> getStatus(
            @AuthenticationPrincipal UserPrincipal principal
    ) throws ChallengeSphereException {
        return ResponseEntity.ok(
                subscriptionService.getStatus(SecurityAccess.requireUserId(principal).toString())
        );
    }

    @PostMapping("/me/sync")
    public ResponseEntity<SubscriptionStatusResponse> sync(
            @AuthenticationPrincipal UserPrincipal principal
    ) throws ChallengeSphereException {
        return ResponseEntity.ok(
                subscriptionService.sync(SecurityAccess.requireUserId(principal).toString())
        );
    }

    @PostMapping("/webhooks/revenuecat")
    public ResponseEntity<Void> revenueCatWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-RevenueCat-Webhook-Signature", required = false) String signature
    ) throws ChallengeSphereException {
        subscriptionService.processRevenueCatWebhook(rawBody, authorization, signature);
        return ResponseEntity.ok().build();
    }
}
