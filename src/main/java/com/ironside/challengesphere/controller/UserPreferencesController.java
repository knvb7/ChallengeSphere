package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.UpdateUserPreferencesRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.service.impl.UserPreferencesServiceImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/{userId}/preferences")
public class UserPreferencesController {

    private static final Logger logger = LogManager.getLogger(UserPreferencesController.class);

    @Autowired
    private UserPreferencesServiceImpl preferencesService;

    /**
     * GET /api/v1/users/{userId}/preferences
     * Returns current preferences. Creates defaults if first time.
     */
    @GetMapping
    public ResponseEntity<UserPreferences> getPreferences(
            @PathVariable Long userId) throws ChallengeSphereException {
        logger.info("Get preferences for userId: {}", userId);
        return ResponseEntity.ok(preferencesService.getPreferences(userId));
    }

    /**
     * PATCH /api/v1/users/{userId}/preferences
     * Updates only the fields provided in the request body.
     */
    @PatchMapping
    public ResponseEntity<UserPreferences> updatePreferences(
            @PathVariable Long userId,
            @RequestBody UpdateUserPreferencesRequest request) throws ChallengeSphereException {
        logger.info("Update preferences for userId: {}", userId);
        return ResponseEntity.ok(preferencesService.updatePreferences(userId, request));
    }

    /**
     * DELETE /api/v1/users/{userId}/preferences/reset
     * Resets all preferences back to defaults.
     */
    @DeleteMapping("/reset")
    public ResponseEntity<UserPreferences> resetPreferences(
            @PathVariable Long userId) throws ChallengeSphereException {
        logger.info("Reset preferences for userId: {}", userId);
        return ResponseEntity.ok(preferencesService.resetPreferences(userId));
    }
}

