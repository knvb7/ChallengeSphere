package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.ChallengeCrewRecapResponse;
import com.ironside.challengesphere.api.response.ChallengeCrewStatusResponse;
import com.ironside.challengesphere.api.response.ChallengeRematchResponse;
import com.ironside.challengesphere.api.response.PeerNudgeResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeCrewInvite;
import com.ironside.challengesphere.model.ChallengeCrewLifecycle;
import com.ironside.challengesphere.model.ChallengePeerNudge;
import com.ironside.challengesphere.model.ChallengePeerReminderPreference;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeCrewInviteRepository;
import com.ironside.challengesphere.repository.ChallengeCrewLifecycleRepository;
import com.ironside.challengesphere.repository.ChallengePeerNudgeRepository;
import com.ironside.challengesphere.repository.ChallengePeerReminderPreferenceRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.ChallengeCrewService;
import com.ironside.challengesphere.service.ChallengeService;
import com.ironside.challengesphere.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ChallengeCrewServiceImpl implements ChallengeCrewService {
    private static final Logger logger = LogManager.getLogger(ChallengeCrewServiceImpl.class);
    private static final int MINIMUM_START_COUNT = 1;
    private static final int RECOMMENDED_READY_COUNT = 3;
    private static final Duration NUDGE_COOLDOWN = Duration.ofHours(24);

    private final ChallengeRepository challengeRepository;
    private final UserChallengeRepository userChallengeRepository;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final ChallengeCrewLifecycleRepository lifecycleRepository;
    private final ChallengeCrewInviteRepository inviteRepository;
    private final ChallengePeerReminderPreferenceRepository reminderPreferenceRepository;
    private final ChallengePeerNudgeRepository nudgeRepository;
    private final ChallengeService challengeService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ChallengeCrewStatusResponse getStatus(Long challengeId, Long actorUserId)
            throws ChallengeSphereException {
        Challenge challenge = requireChallenge(challengeId);
        requireCanView(challenge, actorUserId);
        ChallengeCrewLifecycle lifecycle = findOrCreateLegacyLifecycle(challenge);
        return buildStatus(challenge, lifecycle, actorUserId);
    }

    @Override
    @Transactional
    public ChallengeCrewStatusResponse startTogether(Long challengeId, Long actorUserId)
            throws ChallengeSphereException {
        Challenge challenge = requireChallenge(challengeId);
        requirePrivate(challenge);
        requireOwner(challenge, actorUserId);

        ChallengeCrewLifecycle lifecycle = lifecycleRepository.findByChallengeIdForUpdate(challengeId)
                .orElseGet(() -> findOrCreateLegacyLifecycle(challenge));
        if (lifecycle.getStartedAt() != null) {
            return buildStatus(challenge, lifecycle, actorUserId);
        }
        if (activeParticipants(challengeId).size() < MINIMUM_START_COUNT) {
            throw badRequest("At least one active participant is required to start this challenge");
        }

        Timestamp now = now();
        lifecycle.setStartedAt(now);
        lifecycle.setStartedByUserId(actorUserId);
        lifecycle.setUpdatedAt(now);
        lifecycleRepository.save(lifecycle);

        challenge.setStartDate(LocalDate.now());
        challengeRepository.save(challenge);
        return buildStatus(challenge, lifecycle, actorUserId);
    }

    @Override
    @Transactional
    public ChallengeCrewStatusResponse setPeerReminders(
            Long challengeId,
            Long actorUserId,
            boolean enabled
    ) throws ChallengeSphereException {
        Challenge challenge = requireChallenge(challengeId);
        requirePrivate(challenge);
        requireActiveParticipant(challengeId, actorUserId, "Only active crew members can change reminder consent");

        Timestamp now = now();
        ChallengePeerReminderPreference preference = reminderPreferenceRepository
                .findByChallengeIdAndUserId(challengeId, actorUserId)
                .orElseGet(() -> {
                    ChallengePeerReminderPreference created = new ChallengePeerReminderPreference();
                    created.setChallengeId(challengeId);
                    created.setUserId(actorUserId);
                    created.setCreatedAt(now);
                    return created;
                });
        preference.setEnabled(enabled);
        preference.setUpdatedAt(now);
        reminderPreferenceRepository.save(preference);

        ChallengeCrewLifecycle lifecycle = findOrCreateLegacyLifecycle(challenge);
        return buildStatus(challenge, lifecycle, actorUserId);
    }

    @Override
    @Transactional
    public PeerNudgeResponse sendPeerNudge(Long challengeId, Long actorUserId, Long recipientUserId)
            throws ChallengeSphereException {
        Challenge challenge = requireChallenge(challengeId);
        requirePrivate(challenge);
        requireActiveParticipant(challengeId, actorUserId, "Only active crew members can send a check-in");
        requireActiveParticipant(challengeId, recipientUserId, "That person is no longer an active crew member");
        if (Objects.equals(actorUserId, recipientUserId)) {
            throw badRequest("Choose another crew member for a check-in");
        }
        if (userBlockRepository.existsActiveBlockBetween(actorUserId, recipientUserId)) {
            throw forbidden("This crew check-in is not available");
        }

        ChallengeCrewLifecycle lifecycle = findOrCreateLegacyLifecycle(challenge);
        if (lifecycle.getStartedAt() == null || isComplete(challenge, lifecycle, activeParticipants(challengeId))) {
            throw badRequest("Peer check-ins are available while the challenge is active");
        }

        ChallengePeerReminderPreference preference = reminderPreferenceRepository
                .findByChallengeIdAndUserIdForUpdate(challengeId, recipientUserId)
                .orElseThrow(() -> badRequest("This crew member has not opted in to peer check-ins"));
        if (!Boolean.TRUE.equals(preference.getEnabled())) {
            throw badRequest("This crew member has not opted in to peer check-ins");
        }

        Instant currentInstant = Instant.now();
        Instant nextAllowedInstant = preference.getLastNudgedAt() == null
                ? null
                : preference.getLastNudgedAt().toInstant().plus(NUDGE_COOLDOWN);
        if (nextAllowedInstant != null && currentInstant.isBefore(nextAllowedInstant)) {
            throw new ChallengeSphereException(
                    "This crew member has already received a check-in in the last 24 hours",
                    HttpStatus.TOO_MANY_REQUESTS,
                    logger
            );
        }

        Timestamp sentAt = Timestamp.from(currentInstant);
        preference.setLastNudgedAt(sentAt);
        preference.setUpdatedAt(sentAt);
        reminderPreferenceRepository.save(preference);

        ChallengePeerNudge nudge = new ChallengePeerNudge();
        nudge.setChallengeId(challengeId);
        nudge.setSenderUserId(actorUserId);
        nudge.setRecipientUserId(recipientUserId);
        nudge.setSentAt(sentAt);
        nudgeRepository.save(nudge);

        User sender = userRepository.findById(actorUserId)
                .orElseThrow(() -> notFound("Sender user was not found"));
        String senderName = sender.getUsername() == null || sender.getUsername().isBlank()
                ? "A crew member"
                : "@" + sender.getUsername();
        String message = senderName + " sent a quiet check-in for " + challenge.getTitle()
                + ". Join in when it works for you.";
        notificationService.notifyChallengeUpdate(
                recipientUserId,
                message,
                nudgeActionObject(challenge, sender)
        );

        return PeerNudgeResponse.builder()
                .challengeId(challengeId)
                .recipientUserId(recipientUserId)
                .sentAt(sentAt)
                .nextAllowedAt(Timestamp.from(currentInstant.plus(NUDGE_COOLDOWN)))
                .message("Quiet check-in sent")
                .build();
    }

    @Override
    @Transactional
    public ChallengeCrewRecapResponse getRecap(Long challengeId, Long actorUserId)
            throws ChallengeSphereException {
        Challenge challenge = requireChallenge(challengeId);
        requireCanView(challenge, actorUserId);
        ChallengeCrewLifecycle lifecycle = findOrCreateLegacyLifecycle(challenge);
        List<UserChallenge> participants = activeParticipants(challengeId);
        if (!isComplete(challenge, lifecycle, participants)) {
            throw badRequest("The group recap will be ready when this challenge is complete");
        }
        markRecapReady(lifecycle);

        Set<Long> participantIds = participants.stream()
                .map(UserChallenge::getUserId)
                .collect(java.util.stream.Collectors.toSet());
        List<Submission> submissions = submissionRepository.findActiveByChallengeId(challengeId).stream()
                .filter(submission -> participantIds.contains(submission.getUserId()))
                .toList();
        Set<Long> contributorIds = submissions.stream()
                .map(Submission::getUserId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> checkIns = new HashSet<>();
        for (Submission submission : submissions) {
            checkIns.add(submission.getUserId() + ":" + submission.getDayNumber());
        }

        int participantCount = participants.size();
        int perPersonTarget = Challenge.isOneOffMode(challenge.getChallengeMode())
                ? 1
                : Math.max(1, challenge.getNumberOfDays());
        long possible = (long) participantCount * perPersonTarget;
        int totalPossible = possible > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) possible;
        int completionPercent = totalPossible == 0
                ? 0
                : (int) Math.min(100, Math.round((checkIns.size() * 100.0d) / totalPossible));

        return ChallengeCrewRecapResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challenge.getTitle())
                .participantCount(participantCount)
                .contributorCount(contributorIds.size())
                .checkInCount(checkIns.size())
                .totalPossibleCheckIns(totalPossible)
                .collectiveCompletionPercent(completionPercent)
                .recapReadyAt(lifecycle.getRecapReadyAt())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChallengeRematchResponse rematch(Long challengeId, Long actorUserId)
            throws ChallengeSphereException {
        Challenge source = requireChallenge(challengeId);
        requirePrivate(source);
        requireOwner(source, actorUserId);
        ChallengeCrewLifecycle sourceLifecycle = lifecycleRepository.findByChallengeIdForUpdate(challengeId)
                .orElseGet(() -> findOrCreateLegacyLifecycle(source));
        List<UserChallenge> sourceParticipants = activeParticipants(challengeId);
        if (!isComplete(source, sourceLifecycle, sourceParticipants)) {
            throw badRequest("Rematch is available after the challenge is complete");
        }
        markRecapReady(sourceLifecycle);

        ChallengeCrewLifecycle existing = lifecycleRepository.findByRematchOfChallengeId(challengeId).orElse(null);
        if (existing != null) {
            int copiedInvites = inviteRepository.findByChallengeId(existing.getChallengeId()).size();
            return rematchResponse(challengeId, existing.getChallengeId(), copiedInvites, true);
        }

        List<Long> invitedUserIds = sourceParticipants.stream()
                .map(UserChallenge::getUserId)
                .filter(userId -> !Objects.equals(userId, actorUserId))
                .distinct()
                .toList();
        ChallengeCreateRequest request = copyRequest(source, actorUserId, invitedUserIds);
        ChallengeCreateResponse created = challengeService.createChallenge(request);
        Long rematchChallengeId = created.getChallengeId();

        Timestamp createdAt = now();
        for (Long invitedUserId : invitedUserIds) {
            if (inviteRepository.findByChallengeIdAndInvitedUserId(rematchChallengeId, invitedUserId).isPresent()) {
                continue;
            }
            ChallengeCrewInvite invite = new ChallengeCrewInvite();
            invite.setChallengeId(rematchChallengeId);
            invite.setInvitedUserId(invitedUserId);
            invite.setInvitedByUserId(actorUserId);
            invite.setCreatedAt(createdAt);
            invite.setUpdatedAt(createdAt);
            inviteRepository.save(invite);
        }

        ChallengeCrewLifecycle rematchLifecycle = lifecycleRepository.findByChallengeIdForUpdate(rematchChallengeId)
                .orElseGet(() -> {
                    ChallengeCrewLifecycle createdLifecycle = new ChallengeCrewLifecycle();
                    createdLifecycle.setChallengeId(rematchChallengeId);
                    createdLifecycle.setCreatedAt(createdAt);
                    createdLifecycle.setUpdatedAt(createdAt);
                    return createdLifecycle;
                });
        rematchLifecycle.setRematchOfChallengeId(challengeId);
        rematchLifecycle.setStartedAt(null);
        rematchLifecycle.setStartedByUserId(null);
        rematchLifecycle.setUpdatedAt(createdAt);
        lifecycleRepository.save(rematchLifecycle);
        return rematchResponse(challengeId, rematchChallengeId, invitedUserIds.size(), false);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireStarted(Long challengeId) throws ChallengeSphereException {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> notFound("Challenge not found with id: " + challengeId));
        if (!Boolean.TRUE.equals(challenge.getIsPrivate())) {
            return;
        }
        ChallengeCrewLifecycle lifecycle = lifecycleRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "This private challenge is not ready for check-ins. Please try again shortly.",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        logger
                ));
        if (lifecycle.getStartedAt() == null) {
            throw badRequest("This private challenge is still in the lobby. "
                    + "The creator needs to start it before check-ins open.");
        }
    }

    private ChallengeCrewStatusResponse buildStatus(
            Challenge challenge,
            ChallengeCrewLifecycle lifecycle,
            Long actorUserId
    ) {
        List<UserChallenge> participants = activeParticipants(challenge.getId());
        Set<Long> acceptedUserIds = participants.stream()
                .map(UserChallenge::getUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ChallengeCrewInvite> invitations = inviteRepository.findByChallengeId(challenge.getId());
        int pendingInviteCount = (int) invitations.stream()
                .map(ChallengeCrewInvite::getInvitedUserId)
                .distinct()
                .filter(userId -> !acceptedUserIds.contains(userId))
                .count();
        List<Long> optedInUserIds = reminderPreferenceRepository
                .findByChallengeIdAndEnabledTrue(challenge.getId()).stream()
                .map(ChallengePeerReminderPreference::getUserId)
                .filter(acceptedUserIds::contains)
                .distinct()
                .toList();
        boolean complete = isComplete(challenge, lifecycle, participants);
        if (complete) {
            markRecapReady(lifecycle);
        }
        ChallengeCrewLifecycle rematch = lifecycleRepository
                .findByRematchOfChallengeId(challenge.getId())
                .orElse(null);
        boolean lobby = Boolean.TRUE.equals(challenge.getIsPrivate()) && lifecycle.getStartedAt() == null;
        String state = lobby ? "LOBBY" : (complete ? "COMPLETED" : "ACTIVE");
        boolean owner = isOwner(challenge, actorUserId);
        boolean remindersEnabled = reminderPreferenceRepository
                .findByChallengeIdAndUserId(challenge.getId(), actorUserId)
                .map(ChallengePeerReminderPreference::getEnabled)
                .orElse(false);

        return ChallengeCrewStatusResponse.builder()
                .challengeId(challenge.getId())
                .state(state)
                .privateChallenge(Boolean.TRUE.equals(challenge.getIsPrivate()))
                .canManageCrew(owner)
                .canStart(lobby && owner && acceptedUserIds.size() >= MINIMUM_START_COUNT)
                .acceptedCount(acceptedUserIds.size())
                .invitedCount((int) invitations.stream().map(ChallengeCrewInvite::getInvitedUserId).distinct().count())
                .pendingInviteCount(pendingInviteCount)
                .minimumStartCount(MINIMUM_START_COUNT)
                .recommendedReadyCount(RECOMMENDED_READY_COUNT)
                .startedAt(lifecycle.getStartedAt())
                .startedByUserId(lifecycle.getStartedByUserId())
                .peerRemindersEnabled(remindersEnabled)
                .reminderOptedInUserIds(optedInUserIds)
                .recapAvailable(complete)
                .rematchAvailable(complete && owner && Boolean.TRUE.equals(challenge.getIsPrivate()))
                .rematchChallengeId(rematch == null ? null : rematch.getChallengeId())
                .build();
    }

    private ChallengeCreateRequest copyRequest(Challenge source, Long actorUserId, List<Long> invitedUserIds) {
        ChallengeCreationContentPolicy.PreviewContent content =
                ChallengeCreationContentPolicy.parseStoredContent(source.getDescription(), source.getBenefits());
        String rules = content.rules();
        if (rules == null || rules.length() < ChallengeCreationContentPolicy.RULES_MIN_LENGTH) {
            rules = "Follow the same shared expectations as the previous crew challenge.";
        }

        ChallengeCreateRequest request = new ChallengeCreateRequest();
        request.setTitle(source.getTitle());
        request.setDescription(content.description());
        request.setRules(rules);
        request.setBenefits(content.benefits());
        request.setCreatorUserId(actorUserId);
        request.setHashtags(source.getHashtags());
        request.setCategories(source.getCategories());
        request.setIsPrivate(true);
        request.setStartDate(LocalDate.now());
        request.setNumberOfDays(source.getNumberOfDays());
        request.setChallengeMode(source.getChallengeMode());
        request.setProofMode(source.getProofMode());
        request.setCoverPhotoUrls(splitValues(source.getCoverPhotoUrls()));
        request.setInvitedUserIds(invitedUserIds);
        return request;
    }

    private List<String> splitValues(String rawValues) {
        if (rawValues == null || rawValues.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawValues.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private String nudgeActionObject(Challenge challenge, User sender) {
        Map<String, Object> action = Map.of(
                "actionType", "PEER_CHECK_IN",
                "challengeId", challenge.getId(),
                "challengeTitle", challenge.getTitle(),
                "actorUserId", sender.getId(),
                "actorUsername", sender.getUsername() == null ? "" : sender.getUsername()
        );
        try {
            return objectMapper.writeValueAsString(action);
        } catch (JsonProcessingException exception) {
            logger.warn("Could not serialize peer check-in action for challengeId={}", challenge.getId());
            return "{\"actionType\":\"PEER_CHECK_IN\",\"challengeId\":" + challenge.getId() + "}";
        }
    }

    private ChallengeRematchResponse rematchResponse(
            Long sourceChallengeId,
            Long challengeId,
            int copiedInviteCount,
            boolean existing
    ) {
        return ChallengeRematchResponse.builder()
                .sourceChallengeId(sourceChallengeId)
                .challengeId(challengeId)
                .copiedInviteCount(copiedInviteCount)
                .message(existing ? "Your rematch lobby is already ready" : "Rematch lobby created")
                .build();
    }

    private Challenge requireChallenge(Long challengeId) throws ChallengeSphereException {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> notFound("Challenge not found with id: " + challengeId));
        if (Boolean.FALSE.equals(challenge.getActive())) {
            throw notFound("Challenge not found with id: " + challengeId);
        }
        return challenge;
    }

    private void requireCanView(Challenge challenge, Long actorUserId) throws ChallengeSphereException {
        if (!Boolean.TRUE.equals(challenge.getIsPrivate()) || isOwner(challenge, actorUserId)) {
            return;
        }
        requireActiveParticipant(challenge.getId(), actorUserId, "You do not have access to this private crew");
    }

    private void requirePrivate(Challenge challenge) throws ChallengeSphereException {
        if (!Boolean.TRUE.equals(challenge.getIsPrivate())) {
            throw badRequest("Crew controls are only available for private challenges");
        }
    }

    private void requireOwner(Challenge challenge, Long actorUserId) throws ChallengeSphereException {
        if (!isOwner(challenge, actorUserId)) {
            throw forbidden("Only the challenge owner can do that");
        }
    }

    private boolean isOwner(Challenge challenge, Long actorUserId) {
        return Objects.equals(challenge.getCurrentOwnerId(), actorUserId)
                || (challenge.getCurrentOwnerId() == null && Objects.equals(challenge.getCreatorId(), actorUserId));
    }

    private UserChallenge requireActiveParticipant(Long challengeId, Long userId, String message)
            throws ChallengeSphereException {
        UserChallenge membership = userChallengeRepository.findByUserIdAndChallengeId(userId, challengeId)
                .orElseThrow(() -> forbidden(message));
        if (!Boolean.TRUE.equals(membership.getActive()) || Boolean.TRUE.equals(membership.getBanned())) {
            throw forbidden(message);
        }
        return membership;
    }

    private List<UserChallenge> activeParticipants(Long challengeId) {
        return userChallengeRepository.findActiveByChallengeId(challengeId).stream()
                .filter(membership -> !Boolean.TRUE.equals(membership.getBanned()))
                .toList();
    }

    private ChallengeCrewLifecycle findOrCreateLegacyLifecycle(Challenge challenge) {
        return lifecycleRepository.findById(challenge.getId()).orElseGet(() -> {
            Timestamp createdAt = challenge.getCreatedAt() == null ? now() : challenge.getCreatedAt();
            ChallengeCrewLifecycle lifecycle = new ChallengeCrewLifecycle();
            lifecycle.setChallengeId(challenge.getId());
            lifecycle.setStartedAt(createdAt);
            lifecycle.setStartedByUserId(challenge.getCurrentOwnerId());
            lifecycle.setCreatedAt(createdAt);
            lifecycle.setUpdatedAt(now());
            return lifecycleRepository.save(lifecycle);
        });
    }

    private boolean isComplete(
            Challenge challenge,
            ChallengeCrewLifecycle lifecycle,
            List<UserChallenge> participants
    ) {
        if (lifecycle.getStartedAt() == null) {
            return false;
        }
        if (Challenge.isOneOffMode(challenge.getChallengeMode())) {
            boolean everyoneCompleted = !participants.isEmpty() && participants.stream()
                    .allMatch(membership -> Boolean.TRUE.equals(membership.getCompleted()));
            return everyoneCompleted
                    || !Instant.now().isBefore(lifecycle.getStartedAt().toInstant().plus(Duration.ofDays(1)));
        }
        int duration = Math.max(1, challenge.getNumberOfDays());
        return !LocalDate.now().isBefore(challenge.getStartDate().plusDays(duration));
    }

    private void markRecapReady(ChallengeCrewLifecycle lifecycle) {
        if (lifecycle.getRecapReadyAt() != null) {
            return;
        }
        Timestamp readyAt = now();
        lifecycle.setRecapReadyAt(readyAt);
        lifecycle.setUpdatedAt(readyAt);
        lifecycleRepository.save(lifecycle);
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private ChallengeSphereException badRequest(String message) {
        return new ChallengeSphereException(message, HttpStatus.BAD_REQUEST, logger);
    }

    private ChallengeSphereException forbidden(String message) {
        return new ChallengeSphereException(message, HttpStatus.FORBIDDEN, logger);
    }

    private ChallengeSphereException notFound(String message) {
        return new ChallengeSphereException(message, HttpStatus.NOT_FOUND, logger);
    }
}
