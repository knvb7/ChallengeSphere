package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.RemoveParticipantRequest;
import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.impl.ParticipantRemovalHotfixService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/challenges")
public class ParticipantRemovalHotfixController {

    private final ParticipantRemovalHotfixService removalService;

    public ParticipantRemovalHotfixController(ParticipantRemovalHotfixService removalService) {
        this.removalService = removalService;
    }

    @PostMapping("/{challengeId}/participants/{participantUserId}/remove")
    public ResponseEntity<LeaveChallengeResponse> removeParticipant(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long challengeId,
            @PathVariable Long participantUserId,
            @Valid @RequestBody RemoveParticipantRequest request) throws ChallengeSphereException {
        return ResponseEntity.ok(removalService.removeParticipant(
                challengeId,
                SecurityAccess.requireUserId(principal),
                participantUserId,
                request.getReason()));
    }
}
