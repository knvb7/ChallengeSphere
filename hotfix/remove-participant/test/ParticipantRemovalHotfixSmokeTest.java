import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.service.impl.ParticipantRemovalHotfixService;
import com.ironside.challengesphere.service.impl.RedisCacheServiceImpl;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class ParticipantRemovalHotfixSmokeTest {
    public static void main(String[] args) throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RedisCacheServiceImpl cacheService = mock(RedisCacheServiceImpl.class);
        when(jdbcTemplate.queryForMap(anyString(), eq(51L)))
                .thenReturn(Map.of("creator_id", 2L, "title", "Daily challenge"));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(cacheService).invalidateTimeline(7L);

        ParticipantRemovalHotfixService service =
                new ParticipantRemovalHotfixService(jdbcTemplate, cacheService);
        LeaveChallengeResponse response =
                service.removeParticipant(51L, 2L, 7L, "  Repeated spam posts  ");

        if (!response.isSuccess() || !response.getSuccessfulUserIds().equals(java.util.List.of(7L))) {
            throw new AssertionError("Expected successful participant removal");
        }
        verify(jdbcTemplate).queryForMap(anyString(), eq(51L));
        verify(cacheService).invalidateTimeline(7L);
    }
}
