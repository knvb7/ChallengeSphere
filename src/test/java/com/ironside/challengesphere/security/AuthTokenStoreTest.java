package com.ironside.challengesphere.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthTokenStoreTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void storePersistsHashedTokenOnly() {
        AuthTokenStore store = new AuthTokenStore(jdbcTemplate);

        store.store("raw-refresh-token", "refresh", 42L, Duration.ofMinutes(30));

        ArgumentCaptor<String> tokenHashCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(anyString(),
                tokenHashCaptor.capture(),
                eq("refresh"),
                eq(42L),
                any(Timestamp.class));
        verify(jdbcTemplate).update(anyString());

        assertNotEquals("raw-refresh-token", tokenHashCaptor.getValue());
        assertEquals(64, tokenHashCaptor.getValue().length());
    }

    @Test
    void existsReturnsTrueWhenSqlAllowListContainsToken() {
        AuthTokenStore store = new AuthTokenStore(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), eq("access"), eq(42L)))
                .thenReturn(1);

        assertTrue(store.exists("raw-access-token", "access", 42L));
    }

    @Test
    void existsReturnsFalseWhenSqlAllowListDoesNotContainToken() {
        AuthTokenStore store = new AuthTokenStore(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), eq("refresh"), eq(42L)))
                .thenReturn(0);

        assertFalse(store.exists("raw-refresh-token", "refresh", 42L));
    }

    @Test
    void revokeDeletesHashedToken() {
        AuthTokenStore store = new AuthTokenStore(jdbcTemplate);

        store.revoke("raw-refresh-token", "refresh");

        ArgumentCaptor<String> tokenHashCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(anyString(), tokenHashCaptor.capture(), eq("refresh"));
        assertNotEquals("raw-refresh-token", tokenHashCaptor.getValue());
        assertEquals(64, tokenHashCaptor.getValue().length());
    }

    @Test
    void revokeAllForUserDeletesUserTokens() {
        AuthTokenStore store = new AuthTokenStore(jdbcTemplate);

        store.revokeAllForUser(42L);

        verify(jdbcTemplate).update(anyString(), eq(42L));
    }
}
