package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironside.challengesphere.service.PushNotificationSender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ExpoPushNotificationSender implements PushNotificationSender {

    private static final Logger logger = LogManager.getLogger(ExpoPushNotificationSender.class);
    private static final int EXPO_MAX_MESSAGES_PER_REQUEST = 100;
    private static final int MAX_REQUEST_ATTEMPTS = 3;
    private static final long MIN_BATCH_INTERVAL_MILLIS = 200L;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 250L;

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
    public SendResult send(List<PushMessage> messages) {
        return send(messages, MAX_REQUEST_ATTEMPTS);
    }

    @Override
    public SendResult sendOnce(List<PushMessage> messages) {
        return send(messages, 1);
    }

    private SendResult send(List<PushMessage> messages, int maxRequestAttempts) {
        if (messages == null || messages.isEmpty()) {
            return SendResult.empty();
        }
        if (!enabled) {
            Map<String, String> disabled = new LinkedHashMap<>();
            messages.forEach(message -> disabled.put(message.to(), "PUSH_SENDER_DISABLED"));
            return new SendResult(Set.of(), disabled);
        }

        Set<String> acceptedTokens = new LinkedHashSet<>();
        Map<String, String> rejectedTokens = new LinkedHashMap<>();
        for (int start = 0; start < messages.size(); start += EXPO_MAX_MESSAGES_PER_REQUEST) {
            if (start > 0 && !pause(MIN_BATCH_INTERVAL_MILLIS)) {
                rejectBatch(messages.subList(start, messages.size()), rejectedTokens, "SENDER_INTERRUPTED");
                break;
            }
            int end = Math.min(start + EXPO_MAX_MESSAGES_PER_REQUEST, messages.size());
            List<PushMessage> batch = new ArrayList<>(messages.subList(start, end));
            JsonNode response = sendBatch(batch, maxRequestAttempts);
            collectTicketResults(batch, response, acceptedTokens, rejectedTokens);
        }
        return new SendResult(acceptedTokens, rejectedTokens);
    }

    private JsonNode sendBatch(List<PushMessage> batch, int maxRequestAttempts) {
        JsonNode lastResponse = null;
        for (int attempt = 1; attempt <= maxRequestAttempts; attempt++) {
            try {
                lastResponse = restClient.post()
                        .uri(expoPushUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(batch)
                        .retrieve()
                        .body(JsonNode.class);
                if (hasTicketForEveryMessage(lastResponse, batch.size())) {
                    return lastResponse;
                }
                logger.warn("Expo push batch attempt {}/{} returned a malformed ticket response",
                        attempt, maxRequestAttempts);
            } catch (RuntimeException exception) {
                logger.warn("Expo push batch attempt {}/{} failed for {} message(s): {}",
                        attempt,
                        maxRequestAttempts,
                        batch.size(),
                        exception.getClass().getSimpleName());
                if (attempt < maxRequestAttempts
                        && !pause(INITIAL_RETRY_DELAY_MILLIS << (attempt - 1))) {
                    break;
                }
            }
        }
        logger.error("Expo push batch failed after {} attempts for {} message(s)",
                maxRequestAttempts, batch.size());
        return lastResponse;
    }

    private boolean hasTicketForEveryMessage(JsonNode response, int messageCount) {
        if (response == null) {
            return false;
        }
        JsonNode data = response.get("data");
        return (data != null && data.isArray() && data.size() == messageCount)
                || (data != null && data.isObject() && messageCount == 1);
    }

    private void collectTicketResults(
            List<PushMessage> batch,
            JsonNode response,
            Set<String> acceptedTokens,
            Map<String, String> rejectedTokens
    ) {
        if (response == null) {
            rejectBatch(batch, rejectedTokens, "REQUEST_FAILED");
            return;
        }

        JsonNode data = response.get("data");
        List<JsonNode> tickets = new ArrayList<>();
        if (data != null && data.isArray()) {
            data.forEach(tickets::add);
        } else if (data != null && data.isObject() && batch.size() == 1) {
            tickets.add(data);
        }
        if (tickets.size() != batch.size()) {
            rejectBatch(batch, rejectedTokens, "MALFORMED_EXPO_RESPONSE");
            return;
        }

        for (int index = 0; index < batch.size(); index++) {
            PushMessage message = batch.get(index);
            JsonNode ticket = tickets.get(index);
            if ("ok".equalsIgnoreCase(ticket.path("status").asText())) {
                acceptedTokens.add(message.to());
                rejectedTokens.remove(message.to());
                continue;
            }
            String errorCode = ticket.path("details").path("error").asText();
            if (errorCode.isBlank()) {
                errorCode = ticket.path("message").asText("EXPO_TICKET_ERROR");
            }
            rejectedTokens.put(message.to(), errorCode);
        }
    }

    private void rejectBatch(
            List<PushMessage> batch,
            Map<String, String> rejectedTokens,
            String reason
    ) {
        batch.forEach(message -> rejectedTokens.put(message.to(), reason));
    }

    private boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
