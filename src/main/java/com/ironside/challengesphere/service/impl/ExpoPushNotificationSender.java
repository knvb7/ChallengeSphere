package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.service.PushNotificationSender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class ExpoPushNotificationSender implements PushNotificationSender {

    private static final Logger logger = LogManager.getLogger(ExpoPushNotificationSender.class);

    private final RestClient restClient;
    private final boolean enabled;
    private final String expoPushUrl;

    public ExpoPushNotificationSender(RestClient.Builder restClientBuilder,
                                      @Value("${app.push.expo.enabled:true}") boolean enabled,
                                      @Value("${app.push.expo.url:https://exp.host/--/api/v2/push/send}") String expoPushUrl) {
        this.restClient = restClientBuilder.build();
        this.enabled = enabled;
        this.expoPushUrl = expoPushUrl;
    }

    @Override
    public void send(List<PushMessage> messages) {
        if (!enabled || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            restClient.post()
                    .uri(expoPushUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(messages)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            logger.warn("Failed to send {} Expo push notification(s): {}", messages.size(), e.getClass().getSimpleName());
        }
    }
}
