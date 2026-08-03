package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.service.PushNotificationSender;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class ExpoPushNotificationSenderTest {

    @Test
    void sendIncludesDefaultChannelSoundAndHighPriority() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        ExpoPushNotificationSender sender = new ExpoPushNotificationSender(
                restClientBuilder,
                true,
                "https://exp.host/--/api/v2/push/send"
        );

        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        [{
                          "to": "ExpoPushToken[test]",
                          "title": "New follower",
                          "body": "@actor started following you",
                          "data": {"type": "FOLLOW"},
                          "sound": "default",
                          "channelId": "default",
                          "priority": "high"
                        }]
                        """))
                .andRespond(withSuccess("""
                        {"data":[{"status":"ok","id":"ticket-id"}]}
                        """, MediaType.APPLICATION_JSON));

        PushNotificationSender.PushMessage message = new PushNotificationSender.PushMessage(
                "ExpoPushToken[test]",
                "New follower",
                "@actor started following you",
                Map.of("type", "FOLLOW")
        );
        PushNotificationSender.SendResult result = sender.send(List.of(message));

        server.verify();
        assertEquals(message.collapseId(), message.tag());
        assertEquals(24 * 60 * 60, message.ttl());
        assertEquals(1, result.acceptedTokens().size());
        assertTrue(result.accepted("ExpoPushToken[test]"));
        assertTrue(result.rejectedTokens().isEmpty());
    }

    @Test
    void sendReturnsPerTicketAcceptanceAndDeviceNotRegisteredRejection() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        ExpoPushNotificationSender sender = new ExpoPushNotificationSender(
                restClientBuilder,
                true,
                "https://exp.host/--/api/v2/push/send"
        );
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            {"status":"ok","id":"ticket-ok"},
                            {
                              "status":"error",
                              "message":"The device is not registered",
                              "details":{"error":"DeviceNotRegistered"}
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        PushNotificationSender.SendResult result = sender.send(List.of(
                message("ExpoPushToken[valid]"),
                message("ExpoPushToken[invalid]")
        ));

        server.verify();
        assertEquals(Set.of("ExpoPushToken[valid]"), result.acceptedTokens());
        assertEquals(
                Map.of("ExpoPushToken[invalid]", "DeviceNotRegistered"),
                result.rejectedTokens()
        );
    }

    @Test
    void sendRetriesMalformedTicketResponse() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        ExpoPushNotificationSender sender = new ExpoPushNotificationSender(
                restClientBuilder,
                true,
                "https://exp.host/--/api/v2/push/send"
        );
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"errors":[{"code":"INTERNAL_ERROR","message":"Try again"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"data":[{"status":"ok","id":"ticket-after-retry"}]}
                        """, MediaType.APPLICATION_JSON));

        PushNotificationSender.SendResult result = sender.send(
                List.of(message("ExpoPushToken[retry]"))
        );

        server.verify();
        assertTrue(result.accepted("ExpoPushToken[retry]"));
        assertTrue(result.rejectedTokens().isEmpty());
    }

    @Test
    void sendSplitsOneHundredAndOneMessagesIntoExpoSizedBatches() throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        ExpoPushNotificationSender sender = new ExpoPushNotificationSender(
                restClientBuilder,
                true,
                "https://exp.host/--/api/v2/push/send"
        );
        ObjectMapper objectMapper = new ObjectMapper();
        List<PushNotificationSender.PushMessage> messages = IntStream.range(0, 101)
                .mapToObj(index -> message("ExpoPushToken[token-" + index + "]"))
                .toList();

        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andExpect(method(POST))
                .andExpect(content().json(objectMapper.writeValueAsString(messages.subList(0, 100))))
                .andRespond(withSuccess(successTickets(objectMapper, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andExpect(method(POST))
                .andExpect(content().json(objectMapper.writeValueAsString(messages.subList(100, 101))))
                .andRespond(withSuccess(successTickets(objectMapper, 1), MediaType.APPLICATION_JSON));

        PushNotificationSender.SendResult result = sender.send(messages);

        server.verify();
        assertEquals(101, result.acceptedTokens().size());
        assertTrue(result.rejectedTokens().isEmpty());
    }

    private static PushNotificationSender.PushMessage message(String token) {
        return new PushNotificationSender.PushMessage(
                token,
                "ChallengeSphere",
                "Keep going",
                Map.of("type", "REMINDER")
        );
    }

    private static String successTickets(ObjectMapper objectMapper, int count) throws Exception {
        List<Map<String, String>> tickets = IntStream.range(0, count)
                .mapToObj(index -> Map.of(
                        "status", "ok",
                        "id", "ticket-" + index
                ))
                .toList();
        return objectMapper.writeValueAsString(Map.of("data", tickets));
    }
}
