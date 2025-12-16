public interface ChallengeService {
    // ...existing methods...

    void disableOrSoftDeleteChallengeForUser(Long challengeId, Long userId, boolean disable, boolean softDelete);
}
