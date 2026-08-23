package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateInviteLinkRequest;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.request.RedeemInviteRequest;
import com.ironside.challengesphere.api.response.ChallengeInvitesResponse;
import com.ironside.challengesphere.api.response.InviteLinkResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.api.response.RedeemInviteResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeInvite;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.repository.ChallengeInviteRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.InviteRedemptionRepository;
import com.ironside.challengesphere.repository.InviteUsageRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.ChallengeService;
import com.ironside.challengesphere.service.InviteService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Service
public class InviteServiceImpl implements InviteService {
    private static final Logger logger = LogManager.getLogger(InviteServiceImpl.class);

    private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int INVITE_CODE_LENGTH = 12;
    private static final int MIN_LEGACY_INVITE_CODE_LENGTH = 8;
    private static final String INVITE_UNAVAILABLE_MESSAGE = "Invite is invalid or unavailable";
    private static final int DEFAULT_EXPIRY_HOURS = 720; // 30 days
    private static final int MAX_LIMIT = 100;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.invite.base-url:https://challengesphere.com/invite}")
    private String inviteBaseUrl;

    @Autowired
    private ChallengeInviteRepository challengeInviteRepository;

    @Autowired
    private InviteUsageRepository inviteUsageRepository;

    @Autowired
    private InviteRedemptionRepository inviteRedemptionRepository;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private DynamoDbCursorCodec cursorCodec;

    @Override
    @Transactional
    public InviteLinkResponse generateInviteLink(CreateInviteLinkRequest request) throws ChallengeSphereException {
        String challengeId = request.getChallengeId();
        String userId = request.getUserId();

        logger.info("Generating invite link for challenge {} by user {}", challengeId, userId);

        // Validate challenge exists
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found with id: " + challengeId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        // Validate challenge is active
        if (!challenge.getActive()) {
            throw new ChallengeSphereException(
                    "Cannot create invite for inactive challenge",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new ChallengeSphereException(
                    "User not found with id: " + userId,
                    HttpStatus.NOT_FOUND,
                    logger
            );
        }

        boolean privateChallenge = Boolean.TRUE.equals(challenge.getIsPrivate());
        boolean challengeOwner = isChallengeOwner(challenge, userId);

        // Private challenge membership is owner-controlled. Public challenges
        // retain their existing participant-created share-link behavior.
        if (privateChallenge && !challengeOwner) {
            throw new ChallengeSphereException(
                    "Only the challenge owner can generate a private challenge invite link",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (!privateChallenge && !challengeOwner) {
            UserChallenge userChallenge = userChallengeRepository
                    .findByUserIdAndChallengeId(userId, challengeId)
                    .orElse(null);
            if (userChallenge == null
                    || !Boolean.TRUE.equals(userChallenge.getActive())
                    || Boolean.TRUE.equals(userChallenge.getBanned())) {
                throw new ChallengeSphereException(
                        "Only the challenge creator or active participants can generate invite links",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }
        }

        String recipientUserId = null;
        Integer maxUses = request.getMaxUses();
        if (privateChallenge) {
            recipientUserId = request.getRecipientUserId();
            if (recipientUserId != null) {
                recipientUserId = requirePrivateInviteRecipient(challenge, userId, recipientUserId);
                // Direct private invitations remain recipient-bound and single-use.
                maxUses = 1;
            }
        }

        // Generate unique invite code
        String inviteCode = generateUniqueInviteCode();

        // Calculate expiration
        int expiryHours = request.getExpiresInHours() != null ? request.getExpiresInHours() : DEFAULT_EXPIRY_HOURS;
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(expiryHours, ChronoUnit.HOURS));

        // Create and save invite
        ChallengeInvite invite = new ChallengeInvite();
        invite.setChallengeId(challengeId);
        invite.setCreatedBy(userId);
        invite.setRecipientUserId(recipientUserId);
        invite.setInviteCode(inviteCode);
        invite.setExpiresAt(expiresAt);
        invite.setMaxUses(maxUses);
        invite.setUseCount(0);
        invite.setActive(true);
        invite.setCreatedAt(new Timestamp(System.currentTimeMillis()));

        ChallengeInvite savedInvite = challengeInviteRepository.save(invite);

        String inviteUrl = inviteBaseUrl + "/" + inviteCode;

        logger.info("Successfully created invite {} for challenge {}", savedInvite.getId(), challengeId);

        InviteLinkResponse response = InviteLinkResponse.from(
                savedInvite.getId(),
                savedInvite.getInviteCode(),
                inviteUrl,
                challengeId,
                challenge.getTitle(),
                savedInvite.getExpiresAt(),
                savedInvite.getMaxUses(),
                savedInvite.getUseCount(),
                savedInvite.getActive(),
                savedInvite.getCreatedAt()
        );
        response.setRecipientUserId(savedInvite.getRecipientUserId());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RedeemInviteResponse redeemInvite(RedeemInviteRequest request) throws ChallengeSphereException {
        String inviteCode = request.getInviteCode();
        String userId = request.getUserId();

        logger.info("Redeeming invite for user {}", userId);

        if (!hasValidInviteCodeFormat(inviteCode)) {
            throw inviteUnavailable();
        }

        try (ChallengeInviteRepository.InviteCodeLease ignored =
                     challengeInviteRepository.acquireInviteCodeLease(inviteCode)) {
            return redeemInviteWhileLocked(inviteCode, userId);
        }
    }

    private RedeemInviteResponse redeemInviteWhileLocked(String inviteCode, String userId)
            throws ChallengeSphereException {
        ChallengeInvite invite = findInvite(inviteCode);
        boolean alreadyReserved = inviteUsageRepository.existsByInviteIdAndUserId(
                invite.getId(),
                userId
        );
        if (!alreadyReserved) {
            requireAvailableInvite(invite);
        }

        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new ChallengeSphereException(
                    "User not found with id: " + userId,
                    HttpStatus.NOT_FOUND,
                    logger
            );
        }

        // Validate challenge exists and is active
        Challenge challenge = challengeRepository.findById(invite.getChallengeId())
                .orElseThrow(this::inviteUnavailable);

        if (!Boolean.TRUE.equals(challenge.getActive())) {
            throw inviteUnavailable();
        }

        // Recipient-bound invitations can only be redeemed by their intended
        // user. An unbound code is a shareable capability for public or private
        // challenges and may be redeemed by any eligible authenticated user.
        if (invite.getRecipientUserId() != null && !invite.getRecipientUserId().equals(userId)) {
            throw inviteUnavailable();
        }
        if (userBlockRepository.existsActiveBlockBetween(userId, invite.getCreatedBy())
                || userBlockRepository.existsActiveBlockBetween(userId, effectiveOwnerId(challenge))) {
            throw inviteUnavailable();
        }

        // Check if user is already a participant
        UserChallenge existing = userChallengeRepository.findByUserIdAndChallengeId(userId, invite.getChallengeId())
                .orElse(null);
        if (existing != null && Boolean.TRUE.equals(existing.getBanned())) {
            throw new ChallengeSphereException(
                    "You were removed from this challenge by its creator.",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (existing != null && Boolean.TRUE.equals(existing.getActive())) {
            if (alreadyReserved) {
                return RedeemInviteResponse.success(
                        invite.getChallengeId(),
                        challenge.getTitle(),
                        userId
                );
            }
            throw new ChallengeSphereException(
                    "You are already a participant of this challenge",
                    HttpStatus.CONFLICT,
                    logger
            );
        }

        if (!alreadyReserved) {
            try {
                InviteRedemptionRepository.ReservationResult reservation =
                        inviteRedemptionRepository.reserve(invite, userId);
                invite = reservation.invite();
                if (reservation.newlyReserved()
                        && !Boolean.TRUE.equals(invite.getActive())) {
                    logger.info(
                            "Invite {} auto-deactivated after reaching max uses ({})",
                            invite.getId(),
                            invite.getMaxUses()
                    );
                }
            } catch (InviteRedemptionRepository.ReservationUnavailableException
                     | InviteRedemptionRepository.ReservationConflictException conflict) {
                throw inviteUnavailable();
            }
        }

        // Join the user to the challenge using existing join flow
        JoinChallengeRequest joinRequest = new JoinChallengeRequest();
        joinRequest.setChallengeId(invite.getChallengeId());
        joinRequest.setUserIds(List.of(userId));

        JoinChallengeResponse joinResponse = challengeService.joinChallengeFromVerifiedInvite(joinRequest);

        if (joinResponse == null
                || !joinResponse.isSuccess()
                || joinResponse.getSuccessfulUserIds() == null
                || !joinResponse.getSuccessfulUserIds().contains(userId)) {
            UserChallenge joinedMembership = userChallengeRepository
                    .findByUserIdAndChallengeId(userId, invite.getChallengeId())
                    .orElse(null);
            if (joinedMembership != null && Boolean.TRUE.equals(joinedMembership.getActive())) {
                return RedeemInviteResponse.success(
                        invite.getChallengeId(),
                        challenge.getTitle(),
                        userId
                );
            }
            throw new ChallengeSphereException(
                    "Failed to join the challenge: "
                            + (joinResponse == null ? "No join response" : joinResponse.getMessage()),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }

        logger.info("User {} successfully joined challenge {} via invite {}", userId, invite.getChallengeId(), invite.getId());

        return RedeemInviteResponse.success(invite.getChallengeId(), challenge.getTitle(), userId);
    }

    @Override
    public InviteLinkResponse getInviteDetails(String inviteCode) throws ChallengeSphereException {
        logger.debug("Getting invite preview");

        ChallengeInvite invite = findAvailableInvite(inviteCode);
        Challenge challenge = challengeRepository.findById(invite.getChallengeId())
                .orElseThrow(this::inviteUnavailable);

        if (!Boolean.TRUE.equals(challenge.getActive())) {
            throw inviteUnavailable();
        }

        if (Boolean.TRUE.equals(challenge.getIsPrivate())) {
            // Invite-code lookups stay opaque until an authenticated code
            // holder successfully redeems the private invitation.
            InviteLinkResponse opaque = new InviteLinkResponse();
            opaque.setActive(true);
            opaque.setPrivateChallenge(true);
            return opaque;
        }

        String inviteUrl = inviteBaseUrl + "/" + invite.getInviteCode();

        InviteLinkResponse response = InviteLinkResponse.from(
                invite.getId(),
                invite.getInviteCode(),
                inviteUrl,
                invite.getChallengeId(),
                challenge.getTitle(),
                invite.getExpiresAt(),
                invite.getMaxUses(),
                invite.getUseCount(),
                invite.getActive(),
                invite.getCreatedAt()
        );

        ChallengeCreationContentPolicy.PreviewContent content =
                ChallengeCreationContentPolicy.parseStoredContent(
                        challenge.getDescription(), challenge.getBenefits());
        User inviter = userRepository.findById(invite.getCreatedBy()).orElse(null);
        int acceptedCount = invite.getUseCount() == null ? 0 : invite.getUseCount();

        response.setInviterName(inviter == null ? null : firstNonBlank(inviter.getName(), inviter.getUsername()));
        response.setInviterUsername(inviter == null ? null : blankToNull(inviter.getUsername()));
        response.setChallengeDescription(blankToNull(content.description()));
        response.setChallengeRules(blankToNull(content.rules()));
        response.setChallengeBenefits(blankToNull(content.benefits()));
        response.setChallengeCategories(blankToNull(challenge.getCategories()));
        response.setChallengeMode(blankToNull(challenge.getChallengeMode()));
        response.setProofMode(blankToNull(challenge.getProofMode()));
        response.setChallengeStartDate(challenge.getStartDate());
        response.setChallengeDurationDays(challenge.getNumberOfDays());
        response.setParticipantCount(userChallengeRepository.countActiveByChallengeId(challenge.getId()));
        response.setAcceptedCount(acceptedCount);
        // Share links have no recipient roster, so a pending count would be fabricated.
        response.setPendingCount(null);
        response.setRemainingUses(invite.getMaxUses() == null
                ? null
                : Math.max(0, invite.getMaxUses() - acceptedCount));
        response.setPrivateChallenge(Boolean.TRUE.equals(challenge.getIsPrivate()));
        return response;
    }

    @Override
    public ChallengeInvitesResponse getInvitesForChallenge(
            String challengeId,
            String userId,
            int limit,
            String cursor
    ) throws ChallengeSphereException {
        logger.info("Getting invite inventory for challenge {} by user {}", challengeId, userId);

        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found with id: " + challengeId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        boolean isChallengeOwner = isChallengeOwner(challenge, userId);
        if (!isChallengeOwner) {
            throw new ChallengeSphereException(
                    "Only the challenge owner can view its invite links",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        int boundedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        String queryBinding = "invites:v2|challenge=" + challengeId
                + "|viewer=" + userId
                + "|limit=" + boundedLimit;
        Map<String, AttributeValue> exclusiveStartKey = decodeCursor(cursor, queryBinding);
        DynamoPage<ChallengeInvite> invites = challengeInviteRepository.findByChallengeIdPage(
                challengeId,
                boundedLimit,
                exclusiveStartKey
        );
        boolean hasMore = invites.hasMore();

        List<InviteLinkResponse> inviteResponses = invites.items().stream()
                .map(invite -> {
                    InviteLinkResponse response = InviteLinkResponse.from(
                            invite.getId(),
                            invite.getInviteCode(),
                            inviteBaseUrl + "/" + invite.getInviteCode(),
                            invite.getChallengeId(),
                            challenge.getTitle(),
                            invite.getExpiresAt(),
                            invite.getMaxUses(),
                            invite.getUseCount(),
                            invite.getActive(),
                            invite.getCreatedAt()
                    );
                    response.setRecipientUserId(invite.getRecipientUserId());
                    return response;
                })
                .collect(Collectors.toList());

        ChallengeInvitesResponse response = ChallengeInvitesResponse.builder()
                .challengeId(challengeId)
                .invites(inviteResponses)
                .build();
        response.setNextCursor(encodeCursor(queryBinding, invites.lastEvaluatedKey()));
        response.setHasMore(hasMore);
        return response;
    }

    private Map<String, AttributeValue> decodeCursor(
            String cursor,
            String queryBinding
    ) throws ChallengeSphereException {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }
        try {
            return cursorCodec.decode(cursor, queryBinding).lastEvaluatedKey();
        } catch (IllegalArgumentException exception) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private String encodeCursor(
            String queryBinding,
            Map<String, AttributeValue> lastEvaluatedKey
    ) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(lastEvaluatedKey)
        );
    }

    @Override
    @Transactional
    public void deactivateInvite(String inviteId, String userId) throws ChallengeSphereException {
        logger.info("Deactivating invite {} by user {}", inviteId, userId);

        ChallengeInvite invite = challengeInviteRepository.findById(inviteId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Invite not found with id: " + inviteId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        // Validate user has permission (invite creator or challenge creator/owner)
        Challenge challenge = challengeRepository.findById(invite.getChallengeId())
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge associated with this invite no longer exists",
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        boolean isInviteCreator = invite.getCreatedBy().equals(userId);
        boolean isChallengeOwner = isChallengeOwner(challenge, userId);

        if (!isInviteCreator && !isChallengeOwner) {
            throw new ChallengeSphereException(
                    "You do not have permission to deactivate this invite",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        invite.setActive(false);
        challengeInviteRepository.save(invite);

        logger.info("Successfully deactivated invite {} for challenge {}", inviteId, invite.getChallengeId());
    }

    /**
     * Generate a unique 12-character Base62 invite capability.
     */
    private String generateUniqueInviteCode() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String code = generateBase62Code(INVITE_CODE_LENGTH);
            if (challengeInviteRepository.findByInviteCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to allocate a unique invite code");
    }

    private String generateBase62Code(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62_CHARS.charAt(secureRandom.nextInt(BASE62_CHARS.length())));
        }
        return sb.toString();
    }

    private ChallengeInvite findInvite(String inviteCode) throws ChallengeSphereException {
        if (!hasValidInviteCodeFormat(inviteCode)) {
            throw inviteUnavailable();
        }

        return challengeInviteRepository.findByInviteCode(inviteCode)
                .orElseThrow(this::inviteUnavailable);
    }

    private ChallengeInvite findAvailableInvite(String inviteCode) throws ChallengeSphereException {
        ChallengeInvite invite = findInvite(inviteCode);
        requireAvailableInvite(invite);
        return invite;
    }

    private void requireAvailableInvite(ChallengeInvite invite) throws ChallengeSphereException {
        if (!Boolean.TRUE.equals(invite.getActive())
                || (invite.getExpiresAt() != null
                && !invite.getExpiresAt().after(Timestamp.from(Instant.now())))
                || (invite.getMaxUses() != null
                && (invite.getUseCount() == null ? 0 : invite.getUseCount()) >= invite.getMaxUses())) {
            throw inviteUnavailable();
        }
    }

    private boolean hasValidInviteCodeFormat(String inviteCode) {
        if (inviteCode == null
                || inviteCode.length() < MIN_LEGACY_INVITE_CODE_LENGTH
                || inviteCode.length() > INVITE_CODE_LENGTH) {
            return false;
        }
        for (int index = 0; index < inviteCode.length(); index++) {
            if (BASE62_CHARS.indexOf(inviteCode.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }

    private String firstNonBlank(String preferred, String fallback) {
        String normalizedPreferred = blankToNull(preferred);
        return normalizedPreferred == null ? blankToNull(fallback) : normalizedPreferred;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private boolean isChallengeOwner(Challenge challenge, String userId) {
        return challenge != null
                && userId != null
                && userId.equals(effectiveOwnerId(challenge));
    }

    private String effectiveOwnerId(Challenge challenge) {
        return challenge == null
                ? null
                : (challenge.getCurrentOwnerId() != null
                ? challenge.getCurrentOwnerId()
                : challenge.getCreatorId());
    }

    private String requirePrivateInviteRecipient(
            Challenge challenge,
            String ownerUserId,
            String recipientUserId) throws ChallengeSphereException {
        if (recipientUserId == null) {
            throw new ChallengeSphereException(
                    "A private challenge invite must name exactly one recipient",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        if (recipientUserId.equals(ownerUserId)
                || recipientUserId.equals(effectiveOwnerId(challenge))) {
            throw new ChallengeSphereException(
                    "The challenge owner cannot be the invite recipient",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        User recipient = userRepository.findById(recipientUserId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Invite recipient not found",
                        HttpStatus.BAD_REQUEST,
                        logger
                ));
        if (!Boolean.TRUE.equals(recipient.getActive())) {
            throw new ChallengeSphereException(
                    "Invite recipient is unavailable",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        if (userBlockRepository.existsActiveBlockBetween(ownerUserId, recipientUserId)) {
            throw new ChallengeSphereException(
                    "Invite recipient is unavailable",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        UserChallenge membership = userChallengeRepository
                .findByUserIdAndChallengeId(recipientUserId, challenge.getId())
                .orElse(null);
        if (membership != null && Boolean.TRUE.equals(membership.getBanned())) {
            throw new ChallengeSphereException(
                    "Invite recipient was removed from this challenge",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (membership != null && Boolean.TRUE.equals(membership.getActive())) {
            throw new ChallengeSphereException(
                    "Invite recipient is already a participant of this challenge",
                    HttpStatus.CONFLICT,
                    logger
            );
        }
        return recipientUserId;
    }

    private ChallengeSphereException inviteUnavailable() {
        return new ChallengeSphereException(
                INVITE_UNAVAILABLE_MESSAGE,
                HttpStatus.NOT_FOUND,
                logger
        );
    }
}
