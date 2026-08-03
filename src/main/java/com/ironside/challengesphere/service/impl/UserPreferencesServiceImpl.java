package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.UpdateUserPreferencesRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.repository.UserPreferencesRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPreferencesServiceImpl {

    private static final Logger logger = LogManager.getLogger(UserPreferencesServiceImpl.class);

    @Autowired
    private UserPreferencesRepository preferencesRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * Get preferences for a user. Creates default preferences if none exist yet.
     */
    @Transactional
    public UserPreferences getPreferences(Long userId) throws ChallengeSphereException {
        validateUserExists(userId);
        return preferencesRepository.findByUserId(userId)
                .orElseGet(() -> createDefaults(userId));
    }

    /**
     * Update only the fields that are non-null in the request.
     * Uses individual @Modifying queries — never touches unrelated columns.
     */
    @Transactional
    public UserPreferences updatePreferences(Long userId, UpdateUserPreferencesRequest req)
            throws ChallengeSphereException {
        validateUserExists(userId);

        // Create defaults if first time updating
        if (!preferencesRepository.existsByUserId(userId)) {
            createDefaults(userId);
        }

        // Notification prefs
        if (req.getPushNotificationsEnabled() != null) {
            preferencesRepository.updatePushNotificationsEnabled(userId, req.getPushNotificationsEnabled());
        }
        if (req.getEmailNotificationsEnabled() != null) {
            preferencesRepository.updateEmailNotificationsEnabled(userId, req.getEmailNotificationsEnabled());
        }
        if (req.getNotifyOnGlory() != null) {
            preferencesRepository.updateNotifyOnGlory(userId, req.getNotifyOnGlory());
        }
        if (req.getNotifyOnComment() != null) {
            preferencesRepository.updateNotifyOnComment(userId, req.getNotifyOnComment());
        }
        if (req.getNotifyOnFollow() != null) {
            preferencesRepository.updateNotifyOnFollow(userId, req.getNotifyOnFollow());
        }
        if (req.getNotifyOnChallengeInvite() != null) {
            preferencesRepository.updateNotifyOnChallengeInvite(userId, req.getNotifyOnChallengeInvite());
        }
        if (req.getNotifyOnChallengeUpdate() != null) {
            preferencesRepository.updateNotifyOnChallengeUpdate(userId, req.getNotifyOnChallengeUpdate());
        }
        if (req.getNotifyOnMotivation() != null) {
            preferencesRepository.updateNotifyOnMotivation(userId, req.getNotifyOnMotivation());
        }

        // Privacy prefs
        if (req.getPrivateProfile() != null) {
            preferencesRepository.updatePrivateProfile(userId, req.getPrivateProfile());
        }
        if (req.getPrivateSubmissions() != null) {
            preferencesRepository.updatePrivateSubmissions(userId, req.getPrivateSubmissions());
        }
        if (req.getShowOnLeaderboard() != null) {
            preferencesRepository.updateShowOnLeaderboard(userId, req.getShowOnLeaderboard());
        }
        if (req.getAllowChallengeInvites() != null) {
            preferencesRepository.updateAllowChallengeInvites(userId, req.getAllowChallengeInvites());
        }

        // App prefs
        if (req.getDarkMode() != null) {
            preferencesRepository.updateDarkMode(userId, req.getDarkMode());
        }
        if (req.getLanguage() != null && !req.getLanguage().trim().isEmpty()) {
            preferencesRepository.updateLanguage(userId, req.getLanguage().trim());
        }

        logger.info("Updated preferences for userId: {}", userId);
        return preferencesRepository.findByUserId(userId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Preferences not found after update", HttpStatus.INTERNAL_SERVER_ERROR, logger));
    }

    /**
     * Reset all preferences back to defaults.
     */
    @Transactional
    public UserPreferences resetPreferences(Long userId) throws ChallengeSphereException {
        validateUserExists(userId);
        preferencesRepository.findByUserId(userId)
                .ifPresent(p -> preferencesRepository.deleteById(p.getId()));
        return createDefaults(userId);
    }

    // ==================== HELPERS ====================

    private UserPreferences createDefaults(Long userId) {
        UserPreferences prefs = new UserPreferences();
        prefs.setUserId(userId);
        userRepository.findById(userId).ifPresent(user -> {
            Integer notificationMask = user.getNotifications();
            boolean legacyGlobalPushEnabled = notificationMask == null
                    || (notificationMask & 1) == 1;
            prefs.setNotifyOnMotivation(legacyGlobalPushEnabled);
        });
        UserPreferences saved = preferencesRepository.save(prefs);
        logger.info("Created default preferences for userId: {}", userId);
        return saved;
    }

    private void validateUserExists(Long userId) throws ChallengeSphereException {
        if (!userRepository.existsById(userId)) {
            throw new ChallengeSphereException(
                    "User not found with id: " + userId, HttpStatus.NOT_FOUND, logger);
        }
    }
}
