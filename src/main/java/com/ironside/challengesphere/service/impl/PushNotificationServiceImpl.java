package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.RegisterPushTokenRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.repository.PushDeviceTokenRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.PushNotificationSender;
import com.ironside.challengesphere.service.PushNotificationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PushNotificationServiceImpl implements PushNotificationService {

    private static final Logger logger = LogManager.getLogger(PushNotificationServiceImpl.class);
    private static final Set<String> PUSH_TYPES = Set.of("FOLLOW", "GLORY", "COMMENT", "SUBMISSION", "CHALLENGE_INVITE", "CHALLENGE_UPDATE");
    private static final LocalTime REMINDER_START = LocalTime.of(19, 0);
    private static final LocalTime REMINDER_END = LocalTime.of(20, 0);

    private final PushDeviceTokenRepository tokenRepository;
    private final SubmissionRepository submissionRepository;
    private final PushNotificationSender sender;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public PushNotificationServiceImpl(PushDeviceTokenRepository tokenRepository,
                                       SubmissionRepository submissionRepository,
                                       PushNotificationSender sender) {
        this(tokenRepository, submissionRepository, sender, Clock.systemUTC());
    }

    PushNotificationServiceImpl(PushDeviceTokenRepository tokenRepository,
                                SubmissionRepository submissionRepository,
                                PushNotificationSender sender,
                                Clock clock) {
        this.tokenRepository = tokenRepository;
        this.submissionRepository = submissionRepository;
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PushDeviceToken registerToken(Long userId, RegisterPushTokenRequest request) throws ChallengeSphereException {
        if (userId == null) {
            throw new ChallengeSphereException("Authenticated user required", HttpStatus.UNAUTHORIZED, logger);
        }
        String expoPushToken = safe(request == null ? null : request.getExpoPushToken());
        if (!expoPushToken.startsWith("ExponentPushToken[") && !expoPushToken.startsWith("ExpoPushToken[")) {
            throw new ChallengeSphereException("Invalid Expo push token", HttpStatus.BAD_REQUEST, logger);
        }

        String timezone = normalizeTimezone(request.getTimezone());
        String platform = safe(request.getPlatform()).isBlank() ? "unknown" : safe(request.getPlatform()).toLowerCase();
        Timestamp now = Timestamp.from(clock.instant());

        PushDeviceToken token = tokenRepository.findByExpoPushToken(expoPushToken).orElseGet(PushDeviceToken::new);
        token.setUserId(userId);
        token.setExpoPushToken(expoPushToken);
        token.setPlatform(platform);
        token.setTimezone(timezone);
        token.setEnabled(true);
        token.setLastSeenAt(now);
        return tokenRepository.save(token);
    }

    @Override
    @Transactional
    public void disableToken(Long userId, String expoPushToken) {
        if (userId == null || safe(expoPushToken).isBlank()) {
            return;
        }
        tokenRepository.disableToken(userId, expoPushToken.trim());
    }

    @Override
    public void sendInteractionPush(Long userId, String type, String message, String actionObject) {
        String normalizedType = safe(type).toUpperCase();
        if (userId == null || !PUSH_TYPES.contains(normalizedType)) {
            return;
        }

        List<PushDeviceToken> tokens = tokenRepository.findEnabledByUserId(userId);
        if (tokens.isEmpty()) {
            return;
        }

        Map<String, Object> data = parseActionObject(actionObject);
        data.put("type", normalizedType);

        String title = switch (normalizedType) {
            case "FOLLOW" -> "New follower";
            case "GLORY" -> "New like";
            case "COMMENT" -> "New comment";
            case "SUBMISSION" -> "New submission";
            case "CHALLENGE_INVITE" -> "Challenge invite";
            case "CHALLENGE_UPDATE" -> "Challenge update";
            default -> "ChallengeSphere";
        };

        List<PushNotificationSender.PushMessage> messages = tokens.stream()
                .map(token -> new PushNotificationSender.PushMessage(token.getExpoPushToken(), title, message, data))
                .toList();
        sender.send(messages);
    }

    @Override
    @Transactional
    public int sendDailyUploadReminders() {
        List<PushDeviceToken> tokens = tokenRepository.findEnabledReminderTokens();
        List<PushNotificationSender.PushMessage> messages = new ArrayList<>();
        List<PushDeviceToken> updatedTokens = new ArrayList<>();

        for (PushDeviceToken token : tokens) {
            ZoneId zoneId = toZoneId(token.getTimezone());
            ZonedDateTime localNow = ZonedDateTime.now(clock).withZoneSameInstant(zoneId);
            LocalDate localDate = localNow.toLocalDate();
            LocalTime localTime = localNow.toLocalTime();
            if (localTime.isBefore(REMINDER_START) || !localTime.isBefore(REMINDER_END)) {
                continue;
            }
            if (localDate.equals(token.getLastDailyReminderSentDate())) {
                continue;
            }
            if (!submissionRepository.existsIncompleteDailyChallengeForUser(token.getUserId(), localDate)) {
                continue;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "DAILY_UPLOAD_REMINDER");
            data.put("localDate", localDate.toString());
            messages.add(new PushNotificationSender.PushMessage(
                    token.getExpoPushToken(),
                    "Upload reminder",
                    "Complete today's challenge upload before the day ends.",
                    data
            ));
            token.setLastDailyReminderSentDate(localDate);
            updatedTokens.add(token);
        }

        if (!messages.isEmpty()) {
            sender.send(messages);
            tokenRepository.saveAll(updatedTokens);
        }
        return messages.size();
    }

    private Map<String, Object> parseActionObject(String actionObject) {
        if (safe(actionObject).isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(actionObject, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String normalizeTimezone(String timezone) throws ChallengeSphereException {
        String value = safe(timezone).isBlank() ? "UTC" : timezone.trim();
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException e) {
            throw new ChallengeSphereException("Invalid timezone", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private ZoneId toZoneId(String timezone) {
        try {
            return ZoneId.of(safe(timezone).isBlank() ? "UTC" : timezone);
        } catch (DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
