package com.ironside.challengesphere.api.controller;

import com.ironside.challengesphere.api.request.ChallengeRequest;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import com.ironside.challengesphere.api.response.MessageResponse;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.service.AuthService;
import com.ironside.challengesphere.service.ChallengeService;
import com.ironside.challengesphere.util.ChallengeStatus;
import com.ironside.challengesphere.util.ChallengeType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeService challengeService;
    private final AuthService authService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ChallengeResponse> createChallenge(@Valid @RequestBody ChallengeRequest challengeRequest) {
        User currentUser = authService.getCurrentUser();
        ChallengeResponse newChallenge = challengeService.createChallenge(currentUser.getUserId(), challengeRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(newChallenge);
    }

    @GetMapping
    public ResponseEntity<List<ChallengeResponse>> getAllChallenges(
            @RequestParam(required = false) ChallengeType type,
            @RequestParam(required = false) ChallengeStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        List<ChallengeResponse> challenges = challengeService.findChallenges(type, status, startDate, endDate);
        return ResponseEntity.ok(challenges);
    }

    @GetMapping("/{challengeId}")
    public ResponseEntity<ChallengeResponse> getChallengeById(@PathVariable Long challengeId) {
        ChallengeResponse challenge = challengeService.getChallengeById(challengeId);
        return ResponseEntity.ok(challenge);
    }

    @PutMapping("/{challengeId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> updateChallenge(
            @PathVariable Long challengeId,
            @Valid @RequestBody ChallengeRequest challengeRequest) {
        
        User currentUser = authService.getCurrentUser();
        
        // Check if user is the creator
        if (!challengeService.isUserChallengeCreator(currentUser.getUserId(), challengeId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("Error: Only the challenge creator can update it"));
        }
        
        ChallengeResponse updatedChallenge = challengeService.updateChallenge(challengeId, challengeRequest);
        return ResponseEntity.ok(updatedChallenge);
    }

    @DeleteMapping("/{challengeId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> deleteChallenge(@PathVariable Long challengeId) {
        User currentUser = authService.getCurrentUser();
        
        // Check if user is the creator
        if (!challengeService.isUserChallengeCreator(currentUser.getUserId(), challengeId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("Error: Only the challenge creator can delete it"));
        }
        
        challengeService.deleteChallenge(challengeId);
        return ResponseEntity.ok(new MessageResponse("Challenge deleted successfully"));
    }

    @GetMapping("/by-invite/{inviteCode}")
    public ResponseEntity<ChallengeResponse> getChallengeByInviteCode(@PathVariable String inviteCode) {
        ChallengeResponse challenge = challengeService.getChallengeByInviteCode(inviteCode);
        return ResponseEntity.ok(challenge);
    }

    @GetMapping("/my-challenges")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ChallengeResponse>> getMyCreatedChallenges() {
        User currentUser = authService.getCurrentUser();
        List<ChallengeResponse> myChallenges = challengeService.getCreatedChallenges(currentUser.getUserId());
        return ResponseEntity.ok(myChallenges);
    }

    @GetMapping("/participating")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ChallengeResponse>> getChallengesImParticipatingIn() {
        User currentUser = authService.getCurrentUser();
        List<ChallengeResponse> participatingChallenges = challengeService.getParticipatingChallenges(currentUser.getUserId());
        return ResponseEntity.ok(participatingChallenges);
    }
}
