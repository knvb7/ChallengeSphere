package com.ironside.challengesphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.LoginRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void logoutReturnsSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
    }

    @Test
    void usernameAvailabilityReportsExistingAndOpenUsernames() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserRegistrationRequest registrationRequest = new UserRegistrationRequest();
        registrationRequest.setUsername("taken_" + suffix);
        registrationRequest.setEmailId("taken_" + suffix + "@example.com");
        registrationRequest.setPassword("StrongPass123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationRequest)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/auth/username-availability")
                        .param("username", registrationRequest.getUsername()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(registrationRequest.getUsername()))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.available").value(false));

        mockMvc.perform(get("/api/v1/auth/username-availability")
                        .param("username", "open_" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.available").value(true));

        mockMvc.perform(get("/api/v1/auth/username-availability")
                        .param("username", "bad name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void registerLoginAndFetchMe() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserRegistrationRequest registrationRequest = new UserRegistrationRequest();
        registrationRequest.setUsername("user_" + suffix);
        registrationRequest.setEmailId("user_" + suffix + "@example.com");
        registrationRequest.setPhone(String.valueOf(9000000000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1000000000L)));
        registrationRequest.setPassword("StrongPass123");
        registrationRequest.setBio("test user");

        String registerResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.isNewUser").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode registerJson = objectMapper.readTree(registerResponse);
        String accessToken = registerJson.get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(registrationRequest.getUsername()))
                .andExpect(jsonPath("$.phone").value(registrationRequest.getPhone()));

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(registrationRequest.getUsername());
        loginRequest.setPassword(registrationRequest.getPassword());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.username").value(registrationRequest.getUsername()));
    }
}
