package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.service.ChallengeService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {
    private static final Logger logger = LogManager.getLogger(ChallengeController.class);

    @Autowired
    private ChallengeService challengeService;

    @PostMapping("/create")
    public ResponseEntity<ChallengeCreateResponse> createChallenge(@Valid @RequestBody ChallengeCreateRequest request) throws ChallengeSphereException {
        ChallengeCreateResponse response = challengeService.createChallenge(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/getUserChallenges")
    public ResponseEntity<UserChallengesResponse> getUserChallenges(@Valid @RequestBody GetUserChallengesRequest request) throws ChallengeSphereException {
        UserChallengesResponse response = challengeService.getUserChallenges(request);
        return ResponseEntity.ok(response);
    }
}
