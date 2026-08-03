package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.RegisterPushTokenRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.PushDeviceToken;

import java.util.Set;

public interface PushNotificationService {

    PushDeviceToken registerToken(Long userId, RegisterPushTokenRequest request) throws ChallengeSphereException;

    void disableToken(Long userId, String expoPushToken);

    void sendInteractionPush(Long userId, String type, String message, String actionObject);

    int sendDailyUploadReminders();

    int sendDailyUploadReminders(Set<Long> excludedUserIds);
}
