package com.ironside.challengesphere.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPreferencesDefaultsTest {

    @Test
    void newPreferencesEnableGloryNotificationsByDefault() {
        UserPreferences preferences = new UserPreferences();

        assertTrue(preferences.getNotifyOnGlory());
    }
}
