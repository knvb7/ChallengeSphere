package com.ironside.challengesphere.service;

import java.util.List;

public interface ContentModerationService {

    enum Verdict {
        /** No LIKELY or VERY_LIKELY hits — submission can go through normally. */
        CLEAN,
        /** At least one LIKELY hit — submission is hidden, admin must review. */
        FLAG_FOR_REVIEW,
        /** At least one VERY_LIKELY hit — user is banned and submission rejected. */
        BLOCK_AND_BAN
    }

    /**
     * One finding from a single image. Severity is the raw Vision SafeSearch
     * Likelihood name (POSSIBLE / LIKELY / VERY_LIKELY — we ignore UNKNOWN,
     * VERY_UNLIKELY, UNLIKELY upstream).
     */
    record Finding(String mediaUrl, String category, String severity) {}

    record Result(Verdict verdict, List<Finding> findings) {
        public boolean hasAnyHit() { return !findings.isEmpty(); }
    }

    /**
     * Scan a list of media URLs (image-only; videos / non-images skipped).
     * Never throws — on API failure, returns CLEAN with an empty finding list
     * so moderation outages don't block legitimate submissions. Operators
     * should alert on log-level errors from this path.
     */
    Result scan(List<String> mediaUrls);

    /**
     * Strict scan for asynchronous post-upload moderation.
     * Unlike scan(), Vision client/API/object-read failures must surface to the
     * caller so it can remove optimistically published media.
     */
    Result scanStrict(List<String> mediaUrls);
}
