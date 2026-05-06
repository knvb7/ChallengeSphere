package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectedEndpointRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/challenges/recent"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicHealthEndpointAccessible() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andReturn();
        int status = result.getResponse().getStatus();
        assertNotEquals(401, status);
        assertNotEquals(403, status);
    }
}
