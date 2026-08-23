package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.CreateInviteLinkRequest;
import com.ironside.challengesphere.api.request.RedeemInviteRequest;
import com.ironside.challengesphere.api.response.ChallengeInvitesResponse;
import com.ironside.challengesphere.api.response.InviteLinkResponse;
import com.ironside.challengesphere.api.response.RedeemInviteResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.InviteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/invites")
@RequiredArgsConstructor
public class InviteController {
    private static final Logger logger = LogManager.getLogger(InviteController.class);

    @Autowired
    private InviteService inviteService;

    /**
     * Generate an invite link for a challenge.
     * POST /api/v1/invites
     */
    @PostMapping
    public ResponseEntity<InviteLinkResponse> generateInviteLink(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateInviteLinkRequest request) throws ChallengeSphereException {
        request.setUserId(SecurityAccess.requireUserId(principal).toString());
        logger.info("POST /api/v1/invites - Generate invite for challenge {}", request.getChallengeId());
        InviteLinkResponse response = inviteService.generateInviteLink(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Redeem an invite code to join a challenge.
     * POST /api/v1/invites/redeem
     */
    @PostMapping("/redeem")
    public ResponseEntity<RedeemInviteResponse> redeemInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody RedeemInviteRequest request) throws ChallengeSphereException {
        request.setUserId(SecurityAccess.requireUserId(principal).toString());
        logger.info("POST /api/v1/invites/redeem by user {}", request.getUserId());
        RedeemInviteResponse response = inviteService.redeemInvite(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get invite link details / preview (before joining).
     * GET /api/v1/invites/{inviteCode}
     */
    @GetMapping("/{inviteCode}")
    public ResponseEntity<InviteLinkResponse> getInviteDetails(
            @PathVariable String inviteCode) throws ChallengeSphereException {
        logger.debug("GET /api/v1/invites/{inviteCode}");
        InviteLinkResponse response = inviteService.getInviteDetails(inviteCode);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all invite links for a challenge.
     * GET /api/v1/invites/challenge/{challengeId}
     */
    @GetMapping("/challenge/{challengeId}")
    public ResponseEntity<ChallengeInvitesResponse> getInvitesForChallenge(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String challengeId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) throws ChallengeSphereException {
        String userId = SecurityAccess.requireUserId(principal).toString();
        logger.info("GET /api/v1/invites/challenge/{} by user {}", challengeId, userId);
        ChallengeInvitesResponse response = inviteService.getInvitesForChallenge(
                challengeId,
                userId,
                limit,
                cursor
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Deactivate an invite link owned by the authenticated user or their challenge.
     * DELETE /api/v1/invites/{inviteId}
     */
    @DeleteMapping("/{inviteId}")
    public ResponseEntity<Void> deactivateInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String inviteId) throws ChallengeSphereException {
        String userId = SecurityAccess.requireUserId(principal).toString();
        logger.info("DELETE /api/v1/invites/{} by user {}", inviteId, userId);
        inviteService.deactivateInvite(inviteId, userId);
        return ResponseEntity.noContent().build();
    }
}
