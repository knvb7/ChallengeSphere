package com.ironside.challengesphere.util;

import java.util.Locale;

public final class UsernamePolicy {

    public static final int MIN_LENGTH = 5;
    public static final int MAX_LENGTH = 20;
    public static final String LENGTH_MESSAGE = "Username must be between 5 and 20 characters";
    public static final String RESERVED_KEYWORD = "challengesphere";
    public static final String RESERVED_KEYWORD_MESSAGE = "Username cannot contain \"challengesphere\"";
    private static final String GENERATED_USERNAME_FALLBACK = "challenger";

    private UsernamePolicy() {
    }

    public static boolean containsReservedKeyword(String username) {
        return username != null
                && username.toLowerCase(Locale.ROOT).contains(RESERVED_KEYWORD);
    }

    public static boolean hasValidLength(String username) {
        if (username == null) {
            return false;
        }
        int length = username.trim().length();
        return length >= MIN_LENGTH && length <= MAX_LENGTH;
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
