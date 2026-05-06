package com.ironside.challengesphere.security;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;

public final class SecurityAccess {

    private static final Logger logger = LogManager.getLogger(SecurityAccess.class);

    private SecurityAccess() {
    }

    public static Long requireUserId(UserPrincipal principal) throws ChallengeSphereException {
        if (principal == null || principal.getId() == null) {
            throw new ChallengeSphereException("Authentication required", HttpStatus.UNAUTHORIZED, logger);
        }
        return principal.getId();
    }

    public static void requireSelf(UserPrincipal principal, Long targetUserId) throws ChallengeSphereException {
        Long actorUserId = requireUserId(principal);
        if (targetUserId == null || !actorUserId.equals(targetUserId)) {
            throw new ChallengeSphereException("You can only act as the authenticated user", HttpStatus.FORBIDDEN, logger);
        }
    }
}
