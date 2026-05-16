package com.ironside.challengesphere.controller;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
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
    public ResponseEntity<ChallengeCreateResponse> createChallenge(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChallengeCreateRequest request) throws ChallengeSphereException {
        request.setCreatorUserId(SecurityAccess.requireUserId(principal));
        ChallengeCreateResponse response = challengeService.createChallenge(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<UserChallengesResponse> getUserChallenges(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId,
            @RequestParam(required = false) Long currentUserId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Boolean active) throws ChallengeSphereException {
        GetUserChallengesRequest request = new GetUserChallengesRequest();
        request.setUserId(userId);
        request.setCurrentUserId(SecurityAccess.requireUserId(principal));
        request.setPage(page);
        request.setPageSize(pageSize);
        request.setActive(active);
        UserChallengesResponse response = challengeService.getUserChallenges(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/batch")
    public ResponseEntity<List<Challenge>> getChallengesByIds(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody List<Long> ids) throws ChallengeSphereException {
        List<Challenge> challenges = challengeService.getChallengeByIds(ids, SecurityAccess.requireUserId(principal));
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
    public ResponseEntity<JoinChallengeResponse> joinChallenge(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody JoinChallengeRequest request) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        request.setUserIds(List.of(actorUserId));
        JoinChallengeResponse response = challengeService.joinChallenge(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/leave")
    public ResponseEntity<LeaveChallengeResponse> leaveChallenge(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody LeaveChallengeRequest request) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        request.setUserIds(List.of(actorUserId));
        LeaveChallengeResponse response = challengeService.leaveChallenge(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{challengeId}/participants")
    public ResponseEntity<ChallengeParticipantsResponse> getChallengeParticipants(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long challengeId) throws ChallengeSphereException {
        GetChallengeParticipantsRequest request = new GetChallengeParticipantsRequest();
        request.setChallengeId(challengeId);
        request.setCurrentUserId(SecurityAccess.requireUserId(principal));
        ChallengeParticipantsResponse response = challengeService.getChallengeParticipants(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/by-hashtags")
    public ResponseEntity<ChallengesByHashtagResponse> getChallengesByHashtag(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam List<String> hashtags,
            @RequestParam(required = false) Long currentUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {

        GetChallengesByHashtagRequest request = new GetChallengesByHashtagRequest();
        request.setHashtags(hashtags);
        request.setCurrentUserId(SecurityAccess.requireUserId(principal));
        request.setPage(page);
        request.setPageSize(pageSize);

        ChallengesByHashtagResponse response = challengeService.getChallengesByHashtag(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/popular")
    public ResponseEntity<PopularChallengesResponse> getPopularChallenges(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long currentUserId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {

        GetPopularChallengesRequest request = new GetPopularChallengesRequest();
        request.setCurrentUserId(SecurityAccess.requireUserId(principal));
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
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long currentUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {
        
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("GET /api/v1/challenges/recent - currentUserId: {}, page: {}, pageSize: {}",
                actorUserId, page, pageSize);
        RecentChallengesResponse response = challengeService.getRecentChallenges(page, pageSize, actorUserId);
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
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody ChallengeUpdateRequest request) throws ChallengeSphereException {
        logger.info("PUT /api/v1/challenges/{}", id);
        Long actorUserId = SecurityAccess.requireUserId(principal);
        Challenge updated = challengeService.updateChallenge(id, request, actorUserId, false);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{challengeId}/detail")
    public ResponseEntity<ChallengeDetailResponse> getChallengeDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long challengeId,
            @RequestParam(required = false) Long currentUserId) throws ChallengeSphereException {
        logger.info("GET /api/v1/challenges/{}/detail", challengeId);
        return ResponseEntity.ok(challengeService.getChallengeDetail(challengeId, SecurityAccess.requireUserId(principal)));
    }

    @GetMapping("/{challengeId}/my-progress")
    public ResponseEntity<ChallengeProgressResponse> getChallengeProgress(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long challengeId,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("GET /api/v1/challenges/{}/my-progress?userId={}", challengeId, actorUserId);
        return ResponseEntity.ok(challengeService.getChallengeProgress(challengeId, actorUserId));
    }

    @GetMapping("/my")
    public ResponseEntity<MyChallengesResponse> getMyChallenges(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        Long targetUserId = userId != null ? userId : actorUserId;
        logger.info("GET /api/v1/challenges/my?userId={}&status={}&page={}&pageSize={} actor={}",
                targetUserId, status, page, pageSize, actorUserId);
        return ResponseEntity.ok(challengeService.getMyChallenges(targetUserId, actorUserId, status, page, pageSize));
    }

    @PostMapping("/presigned-urls")
    public ResponseEntity<PresignedUrlsResponse> generateChallengePresignedUrls(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody GeneratePresignedUrlsRequest request) throws ChallengeSphereException {
        request.setUserId(SecurityAccess.requireUserId(principal));
        logger.info("POST /api/v1/challenges/presigned-urls for userId={}", request.getUserId());
        return ResponseEntity.ok(challengeService.generateChallengePresignedUrls(request));
    }

}
