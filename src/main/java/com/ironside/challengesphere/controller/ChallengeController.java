package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import com.ironside.challengesphere.service.ChallengeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {
    @Autowired
    private final ChallengeService challengeService;

    @PostMapping("/create")
    public ResponseEntity<ChallengeResponse> createChallenge(@RequestBody ChallengeCreateRequest request) {
        return ResponseEntity.ok(challengeService.createChallenge(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChallengeResponse> getChallenge(@PathVariable Long id) {
        return ResponseEntity.ok(challengeService.getChallenge(id));
    }

    @GetMapping("/userId")
    public ResponseEntity<List<ChallengeResponse>> getAllChallengesByUserId(@RequestParam Long userId) {
        return ResponseEntity.ok(challengeService.getAllChallengesByUserId(userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ChallengeResponse> updateChallenge(@PathVariable Long id, @RequestBody ChallengeUpdateRequest request) {
        return ResponseEntity.ok(challengeService.updateChallenge(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ChallengeResponse> updateChallengeStatus(@PathVariable Long id, @RequestParam boolean active) {
        return ResponseEntity.ok(challengeService.updateChallengeStatus(id, active));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteChallenge(@PathVariable Long id) {
        challengeService.deleteChallenge(id);
        return ResponseEntity.noContent().build();
    }
}
