package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.HevyConnectRequest;
import com.ironside.challengesphere.api.request.StravaAuthorizeUrlRequest;
import com.ironside.challengesphere.api.request.StravaConnectRequest;
import com.ironside.challengesphere.api.response.HevyConnectionResponse;
import com.ironside.challengesphere.api.response.StravaAuthorizeUrlResponse;
import com.ironside.challengesphere.api.response.StravaConnectionResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.HevyIntegrationService;
import com.ironside.challengesphere.service.StravaIntegrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
public class IntegrationController {
    private final StravaIntegrationService stravaIntegrationService;
    private final HevyIntegrationService hevyIntegrationService;

    @PostMapping("/strava/authorize-url")
    public ResponseEntity<StravaAuthorizeUrlResponse> createStravaAuthorizationUrl(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody StravaAuthorizeUrlRequest request
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(stravaIntegrationService.createAuthorizationUrl(userId, request.redirectUri()));
    }

    @PostMapping("/strava/connect")
    public ResponseEntity<StravaConnectionResponse> connectStrava(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody StravaConnectRequest request
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(stravaIntegrationService.connect(userId, request));
    }

    @GetMapping("/strava")
    public ResponseEntity<StravaConnectionResponse> getStravaConnection(
            @AuthenticationPrincipal UserPrincipal principal
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(stravaIntegrationService.getConnection(userId));
    }

    @PostMapping("/strava/sync")
    public ResponseEntity<StravaConnectionResponse> syncStrava(
            @AuthenticationPrincipal UserPrincipal principal
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(stravaIntegrationService.sync(userId));
    }

    @DeleteMapping("/strava")
    public ResponseEntity<Void> disconnectStrava(
            @AuthenticationPrincipal UserPrincipal principal
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        stravaIntegrationService.disconnect(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/hevy/connect")
    public ResponseEntity<HevyConnectionResponse> connectHevy(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody HevyConnectRequest request
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(hevyIntegrationService.connect(userId, request));
    }

    @GetMapping("/hevy")
    public ResponseEntity<HevyConnectionResponse> getHevyConnection(
            @AuthenticationPrincipal UserPrincipal principal
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(hevyIntegrationService.getConnection(userId));
    }

    @PostMapping("/hevy/sync")
    public ResponseEntity<HevyConnectionResponse> syncHevy(
            @AuthenticationPrincipal UserPrincipal principal
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(hevyIntegrationService.sync(userId));
    }

    @DeleteMapping("/hevy")
    public ResponseEntity<Void> disconnectHevy(
            @AuthenticationPrincipal UserPrincipal principal
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        hevyIntegrationService.disconnect(userId);
        return ResponseEntity.noContent().build();
    }
}
