package com.ironside.challengesphere.persistence.dynamodb.repository.challenge;

import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Primary-key vocabulary shared by the challenge, participation, invite, and
 * motivation read-model repositories.
 *
 * <p>The reminder composer also consumes rows written by sibling repositories:
 * {@code USER#id/PREFERENCES#id},
 * {@code PUSH_TOKENS_ENABLED#id/TOKEN#invertedMillis#id},
 * {@code USER#id/SUBMISSION#CHALLENGE#challenge#DAY#day10#reverseTime#id}, and
 * {@code CHALLENGE#id/LEADERBOARD#STATUS#status#SCORE#invertedScore#id}.</p>
 */
public final class ChallengeRepositoryKeys {

    public static final int REMINDER_SHARDS = 8;
    public static final String CREATED_PREFIX = "CREATED#";
    public static final String ITEM_PREFIX = "ITEM#";
    public static final String USER_CHALLENGE_PREFIX = "USER_CHALLENGE#";
    public static final String SUBMISSION_PREFIX = "SUBMISSION#CHALLENGE#";
    public static final String LEADERBOARD_ACTIVE_PREFIX = "LEADERBOARD#STATUS#ACTIVE#SCORE#";

    private ChallengeRepositoryKeys() {
    }

    public static String challengeCreatorPk(String userId) {
        return join("CHALLENGE", "CREATOR", require(userId, "userId"));
    }

    public static String challengeOwnerPk(String userId) {
        return join("CHALLENGE", "OWNER", require(userId, "userId"));
    }

    public static String challengeStatePk(String state) {
        return DynamoAccessPatterns.collectionPk("CHALLENGE", require(state, "state"));
    }

    public static String challengeStartDatePk(LocalDate date) {
        return join("CHALLENGE", "START_DATE", require(date, "date").toString());
    }

    public static String challengeDurationPk(Integer numberOfDays) {
        if (numberOfDays == null || numberOfDays < 1) {
            throw new IllegalArgumentException("numberOfDays must be positive");
        }
        return join("CHALLENGE", "DURATION", String.format(Locale.ROOT, "%010d", numberOfDays));
    }

    public static String challengeCohortUniquePk(String cohortKey) {
        return join("CHALLENGE", "COHORT", normalize(cohortKey));
    }

    public static String challengeTitleSearchSk(String normalizedTitle, Timestamp createdAt, String id) {
        return join("TITLE", require(normalizedTitle, "normalizedTitle"), epoch(createdAt), require(id, "id"));
    }

    public static String challengeTitleSearchPrefix(String normalizedPrefix) {
        return join("TITLE", require(normalizedPrefix, "normalizedPrefix"));
    }

    public static String createdSk(Timestamp timestamp, String id) {
        return join("CREATED", epoch(timestamp), require(id, "id"));
    }

    public static String userPk(String userId) {
        return DynamoAccessPatterns.entityPk("USER", require(userId, "userId"));
    }

    public static String challengePk(String challengeId) {
        return DynamoAccessPatterns.entityPk("CHALLENGE", require(challengeId, "challengeId"));
    }

    public static String membershipUniquePk(String userId, String challengeId) {
        return join(
                "USER_CHALLENGE",
                "USER", require(userId, "userId"),
                "CHALLENGE", require(challengeId, "challengeId")
        );
    }

    public static String membershipAllSk(Timestamp joinedAt, String id) {
        return join("USER_CHALLENGE", "ALL", epoch(joinedAt), require(id, "id"));
    }

    public static String membershipStatusSk(String status, Timestamp joinedAt, String id) {
        return join(
                "USER_CHALLENGE", "STATUS", require(status, "status").toUpperCase(Locale.ROOT),
                epoch(joinedAt), require(id, "id")
        );
    }

    public static String membershipStatusPrefix(String status) {
        return join("USER_CHALLENGE", "STATUS", require(status, "status").toUpperCase(Locale.ROOT));
    }

    public static String activeMembershipByUserSk(String userId, String membershipId) {
        return join(
                "USER_CHALLENGE", "STATUS", "ACTIVE_USER",
                require(userId, "userId"), require(membershipId, "membershipId")
        );
    }

    public static String activeMembershipByUserPrefix() {
        return join("USER_CHALLENGE", "STATUS", "ACTIVE_USER") + '#';
    }

    public static String activeParticipantCountSk() {
        return "PARTICIPANT_COUNT#ACTIVE";
    }

    public static String membershipFavoriteSk(Timestamp favoritedAt, Timestamp joinedAt, String id) {
        return join(
                "USER_CHALLENGE", "FAVORITE",
                epoch(favoritedAt == null ? joinedAt : favoritedAt), require(id, "id")
        );
    }

    public static String reminderShardPk(String userId) {
        int shard = DynamoAccessPatterns.stableShard(require(userId, "userId"), REMINDER_SHARDS);
        return join("REMINDER", "SHARD", String.format(Locale.ROOT, "%02d", shard));
    }

    public static String reminderCandidateSk(String userId, String challengeId, String membershipId) {
        return join(
                "CANDIDATE", require(userId, "userId"),
                require(challengeId, "challengeId"), require(membershipId, "membershipId")
        );
    }

    public static String inviteCodePk(String inviteCode) {
        return join("CHALLENGE_INVITE", "CODE", normalize(inviteCode));
    }

    public static String inviteChallengePk(String challengeId) {
        return join("CHALLENGE_INVITE", "CHALLENGE", require(challengeId, "challengeId"));
    }

    public static String inviteCreatorPk(String userId) {
        return join("CHALLENGE_INVITE", "CREATOR", require(userId, "userId"));
    }

    public static String inviteRecipientPk(String userId) {
        return join("CHALLENGE_INVITE", "RECIPIENT", require(userId, "userId"));
    }

    public static String inviteUsagePk(String inviteId) {
        return join("INVITE_USAGE", "INVITE", require(inviteId, "inviteId"));
    }

    public static String inviteUsageUniquePk(String inviteId, String userId) {
        return join(
                "INVITE_USAGE", "INVITE", require(inviteId, "inviteId"),
                "USER", require(userId, "userId")
        );
    }

    public static String crewInvitePk(String challengeId) {
        return join("CREW_INVITE", "CHALLENGE", require(challengeId, "challengeId"));
    }

    public static String crewInviteUniquePk(String challengeId, String userId) {
        return join(
                "CREW_INVITE", "CHALLENGE", require(challengeId, "challengeId"),
                "USER", require(userId, "userId")
        );
    }

    public static String lifecycleRematchPk(String challengeId) {
        return join("CREW_LIFECYCLE", "REMATCH_OF", require(challengeId, "challengeId"));
    }

    public static String reminderPreferencePk(String challengeId) {
        return join("PEER_REMINDER", "CHALLENGE", require(challengeId, "challengeId"));
    }

    public static String reminderPreferenceUniquePk(String challengeId, String userId) {
        return join(
                "PEER_REMINDER", "CHALLENGE", require(challengeId, "challengeId"),
                "USER", require(userId, "userId")
        );
    }

    public static String peerNudgeChallengePk(String challengeId) {
        return join("PEER_NUDGE", "CHALLENGE", require(challengeId, "challengeId"));
    }

    public static String peerNudgeSenderPk(String userId) {
        return join("PEER_NUDGE", "SENDER", require(userId, "userId"));
    }

    public static String peerNudgeRecipientPk(String userId) {
        return join("PEER_NUDGE", "RECIPIENT", require(userId, "userId"));
    }

    public static String milestonePk(String userId, String challengeId) {
        return join(
                "MILESTONE", "USER", require(userId, "userId"),
                "CHALLENGE", require(challengeId, "challengeId")
        );
    }

    public static String milestoneUniquePk(String userId, String challengeId, String code) {
        return join(milestonePk(userId, challengeId), "CODE", normalize(code));
    }

    public static String preferencesPrefix() {
        return "PREFERENCES#";
    }

    public static String enabledPushTokensPk(String userId) {
        return join("PUSH_TOKENS_ENABLED", require(userId, "userId"));
    }

    public static String pushTokensPk(String userId) {
        return join("PUSH_TOKENS", require(userId, "userId"));
    }

    public static String pushTokenPrefix() {
        return "TOKEN#";
    }

    public static String submissionChallengePrefix(String challengeId) {
        return join("SUBMISSION", "CHALLENGE", require(challengeId, "challengeId")) + '#';
    }

    public static String submissionDayPrefix(String challengeId, int dayNumber) {
        return join(
                "SUBMISSION", "CHALLENGE", require(challengeId, "challengeId"),
                "DAY", String.format(Locale.ROOT, "%010d", dayNumber)
        ) + '#';
    }

    public static String uniqueSk() {
        return "UNIQUE";
    }

    public static String itemSk(String id) {
        return ITEM_PREFIX + require(id, "id");
    }

    public static String normalize(String value) {
        return DynamoAccessPatterns.normalized(require(value, "value"));
    }

    public static String normalizeHashtag(String value) {
        String normalized = normalize(value);
        while (normalized.startsWith("#")) {
            normalized = normalized.substring(1).strip();
        }
        return require(normalized, "hashtag");
    }

    public static Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    public static String epoch(Timestamp timestamp) {
        long millis = timestamp == null ? 0L : timestamp.getTime();
        return String.format(Locale.ROOT, "%019d", millis);
    }

    public static String join(String first, String... remaining) {
        return DynamoDbKeys.join(first, remaining);
    }

    public static <T> T require(T value, String name) {
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
