package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.SendPeerNudgeRequest;
import com.ironside.challengesphere.api.request.UpdatePeerReminderRequest;
import com.ironside.challengesphere.api.response.ChallengeCrewRecapResponse;
import com.ironside.challengesphere.api.response.ChallengeCrewStatusResponse;
import com.ironside.challengesphere.api.response.ChallengeRematchResponse;
import com.ironside.challengesphere.api.response.PeerNudgeResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.ChallengeCrewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/crew")
@RequiredArgsConstructor
public class ChallengeCrewController {
    private final ChallengeCrewService challengeCrewService;

    @GetMapping
    public ResponseEntity<ChallengeCrewStatusResponse> getStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String challengeId
    ) throws ChallengeSphereException {
        return ResponseEntity.ok(challengeCrewService.getStatus(
                challengeId,
                SecurityAccess.requireUserId(principal).toString()
        ));
    }

    @PostMapping("/start")
    public ResponseEntity<ChallengeCrewStatusResponse> startTogether(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String challengeId
    ) throws ChallengeSphereException {
        return ResponseEntity.ok(challengeCrewService.startTogether(
                challengeId,
                SecurityAccess.requireUserId(principal).toString()
        ));
    }

    @PutMapping("/peer-reminders")
    public ResponseEntity<ChallengeCrewStatusResponse> updatePeerReminders(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String challengeId,
            @Valid @RequestBody UpdatePeerReminderRequest request
    ) throws ChallengeSphereException {
        return ResponseEntity.ok(challengeCrewService.setPeerReminders(
                challengeId,
                SecurityAccess.requireUserId(principal).toString(),
                request.getEnabled()
        ));
    }

    @PostMapping("/nudges")
    public ResponseEntity<PeerNudgeResponse> sendPeerNudge(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String challengeId,
            @Valid @RequestBody SendPeerNudgeRequest request
    ) throws ChallengeSphereException {
        return ResponseEntity.ok(challengeCrewService.sendPeerNudge(
                challengeId,
                SecurityAccess.requireUserId(principal).toString(),
                request.getRecipientUserId()
        ));
    }

    @GetMapping("/recap")
    public ResponseEntity<ChallengeCrewRecapResponse> getRecap(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String challengeId
    ) throws ChallengeSphereException {
        return ResponseEntity.ok(challengeCrewService.getRecap(
                challengeId,
                SecurityAccess.requireUserId(principal).toString()
        ));
    }

    @PostMapping("/rematch")
    public ResponseEntity<ChallengeRematchResponse> rematch(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String challengeId
    ) throws ChallengeSphereException {
        return ResponseEntity.ok(challengeCrewService.rematch(
                challengeId,
                SecurityAccess.requireUserId(principal).toString()
        ));
    }
}
