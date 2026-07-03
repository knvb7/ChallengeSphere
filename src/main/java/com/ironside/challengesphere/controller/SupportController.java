package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.CreateSupportTicketRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SupportTicketResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.SupportService;
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

    public SupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    @PostMapping("/presigned-urls")
    public ResponseEntity<PresignedUrlsResponse> generatePresignedUrls(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody GeneratePresignedUrlsRequest request
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(supportService.generatePresignedUrls(userId, request));
    }

    @PostMapping("/tickets")
    public ResponseEntity<SupportTicketResponse> createTicket(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateSupportTicketRequest request
    ) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        return ResponseEntity.ok(supportService.createTicket(userId, request));
    }
}

