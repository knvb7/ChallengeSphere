package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import com.ironside.challengesphere.service.ChallengeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {
    private final ChallengeService challengeService;

    @PostMapping("/create")
    public ResponseEntity<ChallengeResponse> createChallenge(@RequestBody ChallengeCreateRequest request) {
        return ResponseEntity.ok(challengeService.createChallenge(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChallengeResponse> getChallenge(@PathVariable Long id) {
        return ResponseEntity.ok(challengeService.getChallenge(id));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<List<ChallengeResponse>> getAllChallengesByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(challengeService.getAllChallengesByUserId(userId));
    }
}
