package com.ironside.challengesphere.exception;

import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;

public class ChallengeParticipationLimitException extends ChallengeSphereException {
    public static final String PREMIUM_REQUIRED_CODE = "PREMIUM_REQUIRED_FOR_CHALLENGE_LIMIT";
    public static final String LIMIT_REACHED_CODE = "CHALLENGE_LIMIT_REACHED";

    public ChallengeParticipationLimitException(
            boolean premiumUser,
            String visibility,
            long currentCount,
            int challengeLimit,
            int premiumChallengeLimit,
            Logger logger
    ) {
        super(
                buildMessage(premiumUser, visibility, challengeLimit, premiumChallengeLimit),
                HttpStatus.CONFLICT,
                logger
        );
        getApiError().setCode(premiumUser ? LIMIT_REACHED_CODE : PREMIUM_REQUIRED_CODE);
        getApiError().setUpgradeRequired(!premiumUser);
        getApiError().setTier(premiumUser ? "PREMIUM" : "FREE");
        getApiError().setChallengeVisibility(visibility.toUpperCase());
        getApiError().setCurrentCount(currentCount);
        getApiError().setChallengeLimit(challengeLimit);
    }

    private static String buildMessage(
            boolean premiumUser,
            String visibility,
            int challengeLimit,
            int premiumChallengeLimit
    ) {
        String base = (premiumUser ? "Premium" : "Free")
                + " allows up to " + challengeLimit + " active " + visibility
                + " challenges, including ones you create or join.";
        if (!premiumUser) {
            return base + " Upgrade to Premium for up to " + premiumChallengeLimit
                    + " active " + visibility + " challenges.";
        }
        return base + " Complete or leave one before adding another.";
    }
}
