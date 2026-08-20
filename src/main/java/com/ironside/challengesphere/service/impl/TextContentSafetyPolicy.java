package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A conservative first-pass filter for high-confidence unsafe text. Human reports and
 * moderation remain the fallback for context-dependent abuse that cannot be identified
 * safely with lexical rules alone.
 */
public final class TextContentSafetyPolicy {

    private static final Logger logger = LogManager.getLogger(TextContentSafetyPolicy.class);
    private static final Pattern URL = Pattern.compile("(?i)https?://|www\\.");
    private static final List<Pattern> HIGH_CONFIDENCE_UNSAFE = List.of(
            phrase("kill yourself"),
            phrase("go kill yourself"),
            phrase("child porn"),
            phrase("child pornography"),
            phrase("underage nude"),
            phrase("underage nudes"),
            phrase("send nudes"),
            phrase("trade nudes"),
            phrase("sexual assault challenge"),
            phrase("rape challenge"),
            phrase("shoot up a school"),
            phrase("shoot up the school"),
            phrase("bomb threat")
    );

    private TextContentSafetyPolicy() {}

    public static void requireAllowed(String value, String field) throws ChallengeSphereException {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String normalized = normalize(value);
        boolean unsafe = HIGH_CONFIDENCE_UNSAFE.stream()
                .anyMatch(pattern -> pattern.matcher(normalized).find());
        long urlCount = URL.matcher(value).results().count();
        if (unsafe || urlCount > 3) {
            logger.warn("Rejected unsafe or spam-like text in {}", field);
            throw new ChallengeSphereException(
                    "This " + field + " does not meet the ChallengeSphere content rules",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
    }

    private static Pattern phrase(String value) {
        return Pattern.compile("(^|\\s)" + Pattern.quote(value) + "($|\\s)");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
