package com.ironside.challengesphere.controller;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.CircuitBreaker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ironside.challengesphere.api.request.BulkChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.BulkUserChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.GetChallengeParticipantsRequest;
import com.ironside.challengesphere.api.request.GetChallengesByHashtagRequest;
import com.ironside.challengesphere.api.request.GetPopularChallengesRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.request.LeaveChallengeRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.ChallengeParticipantsResponse;
import com.ironside.challengesphere.api.response.ChallengesByHashtagResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.api.response.PopularChallengesResponse;
import com.ironside.challengesphere.api.response.RecentChallengesResponse;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.service.ChallengeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

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

    @PostMapping("/getByIds")
    public ResponseEntity<List<Challenge>> getChallengesByIds(@RequestBody List<Long> ids) throws ChallengeSphereException {
        List<Challenge> challenges = challengeService.getChallengeByIds(ids);
        return ResponseEntity.ok(challenges);
    }

    @PutMapping("/deleteChallenges")
    public ResponseEntity<Void> deleteChallenges(@RequestBody BulkChallengeUpdateRequest request) {
        challengeService.deleteChallenges(request.getChallengeIds());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/bulkDisableByUsers")
    public ResponseEntity<String> disableChallengesForUsers(
            @RequestBody BulkUserChallengeUpdateRequest request) throws ChallengeSphereException {
        challengeService.disableChallengesForUsers(request.getUserIds());
        return ResponseEntity.ok("Successfully disabled challenges for " + request.getUserIds().size() + " user(s)");
    }

    @PostMapping("/joinChallenge")
    public ResponseEntity<JoinChallengeResponse> joinChallenge(@Valid @RequestBody JoinChallengeRequest request) throws ChallengeSphereException {
        JoinChallengeResponse response = challengeService.joinChallenge(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/leaveChallenge")
    public ResponseEntity<LeaveChallengeResponse> leaveChallenge(@Valid @RequestBody LeaveChallengeRequest request) throws ChallengeSphereException {
        LeaveChallengeResponse response = challengeService.leaveChallenge(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/getParticipants")
    public ResponseEntity<ChallengeParticipantsResponse> getChallengeParticipants(@Valid @RequestBody GetChallengeParticipantsRequest request) throws ChallengeSphereException {
        ChallengeParticipantsResponse response = challengeService.getChallengeParticipants(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/byHashtags")
    public ResponseEntity<ChallengesByHashtagResponse> getChallengesByHashtag(
            @RequestParam List<String> hashtags,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {

        GetChallengesByHashtagRequest request = new GetChallengesByHashtagRequest();
        request.setHashtags(hashtags);
        request.setPage(page);
        request.setPageSize(pageSize);

        ChallengesByHashtagResponse response = challengeService.getChallengesByHashtag(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/popular")
    public ResponseEntity<PopularChallengesResponse> getPopularChallenges(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {

        GetPopularChallengesRequest request = new GetPopularChallengesRequest();
        request.setActive(active);
        if (startDate != null && !startDate.isEmpty()) {
            request.setStartDate(java.time.LocalDate.parse(startDate));
        }
        if (endDate != null && !endDate.isEmpty()) {
            request.setEndDate(java.time.LocalDate.parse(endDate));
        }
        request.setPage(page);
        request.setPageSize(pageSize);

        PopularChallengesResponse response = challengeService.getPopularChallenges(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/recent")
    public ResponseEntity<RecentChallengesResponse> getRecentChallenges(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {
        
        logger.info("GET /api/v1/challenges/recent - page: {}, pageSize: {}", page, pageSize);
        RecentChallengesResponse response = challengeService.getRecentChallenges(page, pageSize);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/trending/ids")
    public ResponseEntity<List<Long>> getTrendingChallengeIds(
            @RequestParam(defaultValue = "10") int limit) {
        
        logger.info("GET /api/v1/challenges/trending/ids - limit: {}", limit);
        List<Long> trendingIds = challengeService.getTrendingChallengeIds(limit);
        return ResponseEntity.ok(trendingIds);
    }
    
    
}