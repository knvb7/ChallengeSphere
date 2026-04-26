package com.ironside.challengesphere.controller;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ironside.challengesphere.api.request.BulkChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.BulkUserChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.request.GetChallengeParticipantsRequest;
import com.ironside.challengesphere.api.request.GetChallengesByHashtagRequest;
import com.ironside.challengesphere.api.request.GetPopularChallengesRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.request.LeaveChallengeRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.ChallengeDetailResponse;
import com.ironside.challengesphere.api.response.ChallengeParticipantsResponse;
import com.ironside.challengesphere.api.response.ChallengeProgressResponse;
import com.ironside.challengesphere.api.response.ChallengesByHashtagResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.api.response.MyChallengesResponse;
import com.ironside.challengesphere.api.response.PopularChallengesResponse;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
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

    @PostMapping
    public ResponseEntity<ChallengeCreateResponse> createChallenge(@Valid @RequestBody ChallengeCreateRequest request) throws ChallengeSphereException {
        ChallengeCreateResponse response = challengeService.createChallenge(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<UserChallengesResponse> getUserChallenges(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Boolean active) throws ChallengeSphereException {
        GetUserChallengesRequest request = new GetUserChallengesRequest();
        request.setUserId(userId);
        request.setPage(page);
        request.setPageSize(pageSize);
        request.setActive(active);
        UserChallengesResponse response = challengeService.getUserChallenges(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/batch")
    public ResponseEntity<List<Challenge>> getChallengesByIds(@RequestBody List<Long> ids) throws ChallengeSphereException {
        List<Challenge> challenges = challengeService.getChallengeByIds(ids);
        return ResponseEntity.ok(challenges);
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteChallenges(@RequestBody BulkChallengeUpdateRequest request) {
        challengeService.deleteChallenges(request.getChallengeIds());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/bulk-disable-by-users")
    public ResponseEntity<String> disableChallengesForUsers(
            @RequestBody BulkUserChallengeUpdateRequest request) throws ChallengeSphereException {
        challengeService.disableChallengesForUsers(request.getUserIds());
        return ResponseEntity.ok("Successfully disabled challenges for " + request.getUserIds().size() + " user(s)");
    }

    @PostMapping("/join")
    public ResponseEntity<JoinChallengeResponse> joinChallenge(@Valid @RequestBody JoinChallengeRequest request) throws ChallengeSphereException {
        JoinChallengeResponse response = challengeService.joinChallenge(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/leave")
    public ResponseEntity<LeaveChallengeResponse> leaveChallenge(@Valid @RequestBody LeaveChallengeRequest request) throws ChallengeSphereException {
        LeaveChallengeResponse response = challengeService.leaveChallenge(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{challengeId}/participants")
    public ResponseEntity<ChallengeParticipantsResponse> getChallengeParticipants(
            @PathVariable Long challengeId) throws ChallengeSphereException {
        GetChallengeParticipantsRequest request = new GetChallengeParticipantsRequest();
        request.setChallengeId(challengeId);
        ChallengeParticipantsResponse response = challengeService.getChallengeParticipants(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/by-hashtags")
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

    @PutMapping("/{id}")
    public ResponseEntity<Challenge> updateChallenge(
            @PathVariable Long id,
            @RequestBody ChallengeUpdateRequest request) throws ChallengeSphereException {
        logger.info("PUT /api/v1/challenges/{}", id);
        Challenge updated = challengeService.updateChallenge(id, request);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{challengeId}/detail")
    public ResponseEntity<ChallengeDetailResponse> getChallengeDetail(
            @PathVariable Long challengeId,
            @RequestParam(required = false) Long currentUserId) throws ChallengeSphereException {
        logger.info("GET /api/v1/challenges/{}/detail", challengeId);
        return ResponseEntity.ok(challengeService.getChallengeDetail(challengeId, currentUserId));
    }

    @GetMapping("/{challengeId}/my-progress")
    public ResponseEntity<ChallengeProgressResponse> getChallengeProgress(
            @PathVariable Long challengeId,
            @RequestParam Long userId) throws ChallengeSphereException {
        logger.info("GET /api/v1/challenges/{}/my-progress?userId={}", challengeId, userId);
        return ResponseEntity.ok(challengeService.getChallengeProgress(challengeId, userId));
    }

    @GetMapping("/my")
    public ResponseEntity<MyChallengesResponse> getMyChallenges(
            @RequestParam Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {
        logger.info("GET /api/v1/challenges/my?userId={}&status={}&page={}&pageSize={}", userId, status, page, pageSize);
        return ResponseEntity.ok(challengeService.getMyChallenges(userId, status, page, pageSize));
    }

    @PostMapping("/presigned-urls")
    public ResponseEntity<PresignedUrlsResponse> generateChallengePresignedUrls(
            @Valid @RequestBody GeneratePresignedUrlsRequest request) throws ChallengeSphereException {
        logger.info("POST /api/v1/challenges/presigned-urls for userId={}", request.getUserId());
        return ResponseEntity.ok(challengeService.generateChallengePresignedUrls(request));
    }
}