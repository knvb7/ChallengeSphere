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
import com.ironside.challengesphere.model.InviteUsage;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeInviteRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.InviteUsageRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
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
import java.util.stream.Collectors;

@Service
public class InviteServiceImpl implements InviteService {
    private static final Logger logger = LogManager.getLogger(InviteServiceImpl.class);

    private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int INVITE_CODE_LENGTH = 8;
    private static final int DEFAULT_EXPIRY_HOURS = 168; // 7 days

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.invite.base-url:https://challengesphere.com/invite}")
    private String inviteBaseUrl;

    @Autowired
    private ChallengeInviteRepository challengeInviteRepository;

    @Autowired
    private InviteUsageRepository inviteUsageRepository;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private ChallengeService challengeService;

    @Override
    @Transactional
    public InviteLinkResponse generateInviteLink(CreateInviteLinkRequest request) throws ChallengeSphereException {
        Long challengeId = request.getChallengeId();
        Long userId = request.getUserId();

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

        // Validate user is either the challenge creator or an active participant
        boolean isCreator = challenge.getCreatorId().equals(userId) || challenge.getCurrentOwnerId().equals(userId);
        if (!isCreator) {
            UserChallenge userChallenge = userChallengeRepository
                    .findByUserIdAndChallengeId(userId, challengeId)
                    .orElse(null);
            if (userChallenge == null || !userChallenge.getActive()) {
                throw new ChallengeSphereException(
                        "Only the challenge creator or active participants can generate invite links",
                        HttpStatus.FORBIDDEN,
                        logger
                );
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
        invite.setInviteCode(inviteCode);
        invite.setExpiresAt(expiresAt);
        invite.setMaxUses(request.getMaxUses());
        invite.setUseCount(0);
        invite.setActive(true);
        invite.setCreatedAt(new Timestamp(System.currentTimeMillis()));

        ChallengeInvite savedInvite = challengeInviteRepository.save(invite);

        String inviteUrl = inviteBaseUrl + "/" + inviteCode;

        logger.info("Successfully created invite link {} for challenge {}", inviteCode, challengeId);

        return InviteLinkResponse.from(
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
    }

    @Override
    @Transactional
    public RedeemInviteResponse redeemInvite(RedeemInviteRequest request) throws ChallengeSphereException {
        String inviteCode = request.getInviteCode();
        Long userId = request.getUserId();

        logger.info("Redeeming invite code {} for user {}", inviteCode, userId);

        // Find the invite
        ChallengeInvite invite = challengeInviteRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Invalid invite code: " + inviteCode,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        // Validate invite is active
        if (!invite.getActive()) {
            throw new ChallengeSphereException(
                    "This invite link has been deactivated",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        // Validate invite has not expired
        if (invite.getExpiresAt() != null && invite.getExpiresAt().before(Timestamp.from(Instant.now()))) {
            throw new ChallengeSphereException(
                    "This invite link has expired",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        // Validate max uses not exceeded
        if (invite.getMaxUses() != null && invite.getUseCount() >= invite.getMaxUses()) {
            throw new ChallengeSphereException(
                    "This invite link has reached its maximum number of uses",
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

        // Check if user already used this invite
        if (inviteUsageRepository.existsByInviteIdAndUserId(invite.getId(), userId)) {
            throw new ChallengeSphereException(
                    "You have already used this invite link",
                    HttpStatus.CONFLICT,
                    logger
            );
        }

        // Validate challenge exists and is active
        Challenge challenge = challengeRepository.findById(invite.getChallengeId())
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge associated with this invite no longer exists",
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (!challenge.getActive()) {
            throw new ChallengeSphereException(
                    "The challenge associated with this invite is no longer active",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        // Check if user is already a participant
        UserChallenge existing = userChallengeRepository.findByUserIdAndChallengeId(userId, invite.getChallengeId())
                .orElse(null);
        if (existing != null && existing.getActive()) {
            throw new ChallengeSphereException(
                    "You are already a participant of this challenge",
                    HttpStatus.CONFLICT,
                    logger
            );
        }

        // Join the user to the challenge using existing join flow
        JoinChallengeRequest joinRequest = new JoinChallengeRequest();
        joinRequest.setChallengeId(invite.getChallengeId());
        joinRequest.setUserIds(List.of(userId));

        JoinChallengeResponse joinResponse = challengeService.joinChallenge(joinRequest);

        if (!joinResponse.isSuccess() || joinResponse.getSuccessfulUserIds().isEmpty()) {
            throw new ChallengeSphereException(
                    "Failed to join the challenge: " + joinResponse.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }

        // Record invite usage
        InviteUsage usage = new InviteUsage();
        usage.setInviteId(invite.getId());
        usage.setUserId(userId);
        usage.setJoinedAt(new Timestamp(System.currentTimeMillis()));
        inviteUsageRepository.save(usage);

        // Increment use count
        invite.setUseCount(invite.getUseCount() + 1);

        // Auto-deactivate if max uses reached
        if (invite.getMaxUses() != null && invite.getUseCount() >= invite.getMaxUses()) {
            invite.setActive(false);
            logger.info("Invite {} auto-deactivated after reaching max uses ({})", inviteCode, invite.getMaxUses());
        }

        challengeInviteRepository.save(invite);

        logger.info("User {} successfully joined challenge {} via invite code {}", userId, invite.getChallengeId(), inviteCode);

        return RedeemInviteResponse.success(invite.getChallengeId(), challenge.getTitle(), userId);
    }

    @Override
    public InviteLinkResponse getInviteDetails(String inviteCode) throws ChallengeSphereException {
        logger.info("Getting invite details for code: {}", inviteCode);

        ChallengeInvite invite = challengeInviteRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Invalid invite code: " + inviteCode,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        Challenge challenge = challengeRepository.findById(invite.getChallengeId())
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge associated with this invite no longer exists",
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        String inviteUrl = inviteBaseUrl + "/" + invite.getInviteCode();

        return InviteLinkResponse.from(
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
    }

    @Override
    public ChallengeInvitesResponse getInvitesForChallenge(Long challengeId) throws ChallengeSphereException {
        logger.info("Getting all invites for challenge: {}", challengeId);

        // Validate challenge exists
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found with id: " + challengeId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        List<ChallengeInvite> invites = challengeInviteRepository.findByChallengeId(challengeId);

        List<InviteLinkResponse> inviteResponses = invites.stream()
                .map(invite -> InviteLinkResponse.from(
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
                ))
                .collect(Collectors.toList());

        return ChallengeInvitesResponse.builder()
                .challengeId(challengeId)
                .invites(inviteResponses)
                .totalCount(inviteResponses.size())
                .build();
    }

    @Override
    @Transactional
    public void deactivateInvite(Long inviteId, Long userId) throws ChallengeSphereException {
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
        boolean isChallengeOwner = challenge.getCreatorId().equals(userId) || challenge.getCurrentOwnerId().equals(userId);

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
     * Generate a unique Base62 invite code (8 characters, URL-friendly).
     */
    private String generateUniqueInviteCode() {
        String code;
        int attempts = 0;
        do {
            code = generateBase62Code(INVITE_CODE_LENGTH);
            attempts++;
            if (attempts > 10) {
                // Extremely unlikely, but prevent infinite loops
                code = generateBase62Code(INVITE_CODE_LENGTH + 2);
            }
        } while (challengeInviteRepository.findByInviteCode(code).isPresent());
        return code;
    }

    private String generateBase62Code(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62_CHARS.charAt(secureRandom.nextInt(BASE62_CHARS.length())));
        }
        return sb.toString();
    }
}

