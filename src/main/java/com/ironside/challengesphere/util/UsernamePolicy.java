package com.ironside.challengesphere.util;

import java.util.Locale;

public final class UsernamePolicy {

    public static final String RESERVED_KEYWORD = "challengesphere";
    public static final String RESERVED_KEYWORD_MESSAGE = "Username cannot contain \"challengesphere\"";
    private static final String GENERATED_USERNAME_FALLBACK = "challenger";

    private UsernamePolicy() {
    }

    public static boolean containsReservedKeyword(String username) {
        return username != null
                && username.toLowerCase(Locale.ROOT).contains(RESERVED_KEYWORD);
    }

    public static String sanitizeGeneratedUsernameBase(String rawBase) {
        String sanitized = rawBase == null
                ? ""
                : rawBase.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_]", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_+|_+$", "");

        if (sanitized.isBlank() || containsReservedKeyword(sanitized)) {
            return GENERATED_USERNAME_FALLBACK;
        }

        return sanitized;
    }
}
