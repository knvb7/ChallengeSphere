package com.ironside.challengesphere.api.controller;

import com.ironside.challengesphere.api.response.MessageResponse;
import com.ironside.challengesphere.api.response.ParticipantResponse;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.service.AuthService;
import com.ironside.challengesphere.service.ParticipationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/participation")
@RequiredArgsConstructor
public class ParticipationController {

    private final ParticipationService participationService;
    private final AuthService authService;

    @PostMapping("/join/{challengeId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> joinChallenge(@PathVariable Long challengeId) {
        User currentUser = authService.getCurrentUser();
        participationService.joinChallenge(currentUser.getUserId(), challengeId);
        return ResponseEntity.ok(new MessageResponse("Joined challenge successfully"));
    }

    @PostMapping("/join-by-invite/{inviteCode}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> joinChallengeByInviteCode(@PathVariable String inviteCode) {
        User currentUser = authService.getCurrentUser();
        participationService.joinChallengeByInviteCode(currentUser.getUserId(), inviteCode);
        return ResponseEntity.ok(new MessageResponse("Joined challenge successfully"));
    }

    @PostMapping("/leave/{challengeId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> leaveChallenge(@PathVariable Long challengeId) {
        User currentUser = authService.getCurrentUser();
        participationService.leaveChallenge(currentUser.getUserId(), challengeId);
        return ResponseEntity.ok(new MessageResponse("Left challenge successfully"));
    }

    @GetMapping("/participants/{challengeId}")
    public ResponseEntity<List<ParticipantResponse>> getParticipants(@PathVariable Long challengeId) {
        List<ParticipantResponse> participants = participationService.getChallengeParticipants(challengeId);
        return ResponseEntity.ok(participants);
    }

    @GetMapping("/progress/{challengeId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Double> getMyProgress(@PathVariable Long challengeId) {
        User currentUser = authService.getCurrentUser();
        Double progress = participationService.getUserProgress(currentUser.getUserId(), challengeId);
        return ResponseEntity.ok(progress);
    }

    @GetMapping("/leaderboard/{challengeId}")
    public ResponseEntity<List<ParticipantResponse>> getChallengeLeaderboard(@PathVariable Long challengeId) {
        List<ParticipantResponse> leaderboard = participationService.getChallengeLeaderboard(challengeId);
        return ResponseEntity.ok(leaderboard);
    }
}
