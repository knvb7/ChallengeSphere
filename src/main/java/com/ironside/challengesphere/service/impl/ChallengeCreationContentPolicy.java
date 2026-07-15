package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChallengeCreationContentPolicy {
    public static final int TITLE_MIN_LENGTH = 5;
    public static final int TITLE_MAX_LENGTH = 80;
    public static final int DESCRIPTION_MIN_LENGTH = 20;
    public static final int DESCRIPTION_MAX_LENGTH = 500;
    public static final int RULES_MIN_LENGTH = 10;
    public static final int RULES_MAX_LENGTH = 1000;
    public static final int BENEFITS_MAX_LENGTH = 1000;
    public static final int CATEGORY_MIN_LENGTH = 2;
    public static final int CATEGORY_MAX_LENGTH = 30;
    public static final int CATEGORY_MAX_COUNT = 5;
    public static final String RULES_HEADER = "Rules & Guidelines:";
    public static final String BENEFITS_HEADER = "Benefits:";

    private static final Logger logger = LogManager.getLogger(ChallengeCreationContentPolicy.class);
    private static final Pattern LEGACY_RULES_HEADER = headerPattern(RULES_HEADER);
    private static final Pattern LEGACY_BENEFITS_HEADER = headerPattern(BENEFITS_HEADER);

    private ChallengeCreationContentPolicy() {
    }

    public static Content validateAndNormalize(ChallengeCreateRequest request) throws ChallengeSphereException {
        if (request == null) {
            throw badRequest("Challenge request is required");
        }

        String title = trim(request.getTitle());
        String description = trim(request.getDescription());
        String rules = trim(request.getRules());
        String benefits = trim(request.getBenefits());

        if (request.getRules() == null) {
            LegacyDescription legacy = parseLegacyDescription(description);
            description = legacy.description();
            rules = legacy.rules();
            if (request.getBenefits() == null) {
                benefits = legacy.benefits();
            }
        }

        validateRange(title, "Title", TITLE_MIN_LENGTH, TITLE_MAX_LENGTH);
        validateRange(description, "Description", DESCRIPTION_MIN_LENGTH, DESCRIPTION_MAX_LENGTH);
        validateRange(rules, "Rules & Guidelines", RULES_MIN_LENGTH, RULES_MAX_LENGTH);
        if (benefits.length() > BENEFITS_MAX_LENGTH) {
            throw badRequest("Benefits must be at most " + BENEFITS_MAX_LENGTH + " characters");
        }

        String categories = normalizeCategories(request);
        String storedDescription = buildStoredDescription(description, rules, benefits);

        return new Content(title, description, rules, benefits, categories, storedDescription);
    }

    private static LegacyDescription parseLegacyDescription(String rawDescription) {
        Matcher rulesHeader = LEGACY_RULES_HEADER.matcher(rawDescription);
        if (!rulesHeader.find()) {
            return new LegacyDescription(rawDescription, "", "");
        }

        Matcher benefitsHeader = LEGACY_BENEFITS_HEADER.matcher(rawDescription);
        int rulesEnd = rulesHeader.end();
        if (benefitsHeader.find(rulesEnd)) {
            return new LegacyDescription(
                    trim(rawDescription.substring(0, rulesHeader.start())),
                    trim(rawDescription.substring(rulesEnd, benefitsHeader.start())),
                    trim(rawDescription.substring(benefitsHeader.end()))
            );
        }

        return new LegacyDescription(
                trim(rawDescription.substring(0, rulesHeader.start())),
                trim(rawDescription.substring(rulesEnd)),
                ""
        );
    }

    private static String normalizeCategories(ChallengeCreateRequest request) throws ChallengeSphereException {
        if (request.getCategoryValues() != null) {
            return normalizeCategoryValues(request.getCategoryValues(), true);
        }

        return normalizeLegacyCategories(request.getCategories());
    }

    private static String normalizeLegacyCategories(String rawCategories) throws ChallengeSphereException {
        String trimmedCategories = trim(rawCategories);
        if (trimmedCategories.isEmpty()) {
            throw badRequest("At least one category is required");
        }

        return normalizeCategoryValues(Arrays.asList(trimmedCategories.split(",", -1)), false);
    }

    private static String normalizeCategoryValues(List<String> values, boolean rejectCommas)
            throws ChallengeSphereException {
        if (values.isEmpty()) {
            throw badRequest("At least one category is required");
        }
        if (values.size() > CATEGORY_MAX_COUNT) {
            throw badRequest("A challenge can have at most " + CATEGORY_MAX_COUNT + " categories");
        }

        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            String category = trim(value);
            if (category.isEmpty()) {
                throw badRequest("Categories cannot contain blank entries");
            }
            if (rejectCommas && category.contains(",")) {
                throw badRequest("Categories cannot contain commas");
            }
            validateRange(category, "Each category", CATEGORY_MIN_LENGTH, CATEGORY_MAX_LENGTH);
            if (!seen.add(category.toLowerCase(Locale.ROOT))) {
                throw badRequest("Categories must be unique");
            }
            normalized.add(category);
        }
        return String.join(",", normalized);
    }

    private static String buildStoredDescription(String description, String rules, String benefits) {
        String stored = description + "\n\n" + RULES_HEADER + "\n" + rules;
        if (!benefits.isEmpty()) {
            stored += "\n\n" + BENEFITS_HEADER + "\n" + benefits;
        }
        return stored;
    }

    private static void validateRange(String value, String field, int minimum, int maximum)
            throws ChallengeSphereException {
        if (value.length() < minimum || value.length() > maximum) {
            throw badRequest(field + " must be between " + minimum + " and " + maximum + " characters");
        }
    }

    private static Pattern headerPattern(String header) {
        return Pattern.compile("(?im)^\\h*" + Pattern.quote(header) + "\\h*");
    }

    private static String trim(String value) {
        return value == null ? "" : value.strip();
    }

    private static ChallengeSphereException badRequest(String message) {
        return new ChallengeSphereException(message, HttpStatus.BAD_REQUEST, logger);
    }

    public record Content(
            String title,
            String description,
            String rules,
            String benefits,
            String categories,
            String storedDescription
    ) {
    }

    private record LegacyDescription(String description, String rules, String benefits) {
    }
}
