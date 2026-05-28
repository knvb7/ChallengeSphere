package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.service.PushNotificationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyUploadReminderScheduler {

    private static final Logger logger = LogManager.getLogger(DailyUploadReminderScheduler.class);

    private final PushNotificationService pushNotificationService;

    @Value("${app.push.daily-reminder.enabled:true}")
    private boolean enabled;

    public DailyUploadReminderScheduler(PushNotificationService pushNotificationService) {
        this.pushNotificationService = pushNotificationService;
    }

    @Scheduled(cron = "${app.push.daily-reminder.cron:0 */15 * * * *}")
    public void sendDailyUploadReminders() {
        if (!enabled) {
            logger.debug("Daily upload push reminders disabled, skipping");
            return;
        }
        int sent = pushNotificationService.sendDailyUploadReminders();
        if (sent > 0) {
            logger.info("Sent {} daily upload push reminder(s)", sent);
        }
    }
}
