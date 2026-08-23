package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.CreateSupportTicketRequest;
import com.ironside.challengesphere.api.request.CreateFeatureRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.FeatureRequestResponse;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SupportTicketResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.SupportService;
import com.ironside.challengesphere.service.FeatureRequestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final SupportService supportService;
    private final FeatureRequestService featureRequestService;

    public SupportController(
            SupportService supportService,
            FeatureRequestService featureRequestService
    ) {
        this.supportService = supportService;
        this.featureRequestService = featureRequestService;
    }

    @PostMapping("/presigned-urls")
    public ResponseEntity<PresignedUrlsResponse> generatePresignedUrls(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody GeneratePresignedUrlsRequest request
    ) throws ChallengeSphereException {
        String userId = SecurityAccess.requireUserId(principal).toString();
        return ResponseEntity.ok(supportService.generatePresignedUrls(userId, request));
    }

    @PostMapping("/tickets")
    public ResponseEntity<SupportTicketResponse> createTicket(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateSupportTicketRequest request
    ) throws ChallengeSphereException {
        String userId = SecurityAccess.requireUserId(principal).toString();
        return ResponseEntity.ok(supportService.createTicket(userId, request));
    }

    @PostMapping("/feature-requests")
    public ResponseEntity<FeatureRequestResponse> createFeatureRequest(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateFeatureRequest request
    ) throws ChallengeSphereException {
        String userId = SecurityAccess.requireUserId(principal).toString();
        return ResponseEntity.ok(featureRequestService.createFeatureRequest(userId, request));
    }
}
