package com.ironside.challengesphere.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernamePolicyTest {

    @Test
    void containsReservedKeywordRejectsChallengeSphereCaseInsensitively() {
        assertTrue(UsernamePolicy.containsReservedKeyword("myChallengeSphere"));
        assertTrue(UsernamePolicy.containsReservedKeyword("challengeSPHERE_fan"));
        assertFalse(UsernamePolicy.containsReservedKeyword("challenge_runner"));
    }

    @Test
    void sanitizeBaseReplacesReservedGoogleNameWithSafeFallback() {
        assertEquals("challenger", UsernamePolicy.sanitizeGeneratedUsernameBase("ChallengeSphere"));
        assertEquals("challenger", UsernamePolicy.sanitizeGeneratedUsernameBase("the_challengesphere_admin"));
        assertEquals("pranjal_dalal", UsernamePolicy.sanitizeGeneratedUsernameBase("Pranjal Dalal"));
    }
}
