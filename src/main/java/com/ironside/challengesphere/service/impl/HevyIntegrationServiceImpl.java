package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.HevyConnectRequest;
import com.ironside.challengesphere.api.response.HevyConnectionResponse;
import com.ironside.challengesphere.api.response.HevyWorkoutSummary;
import com.ironside.challengesphere.api.response.HevyWorkoutSummaryItem;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.UserIntegration;
import com.ironside.challengesphere.repository.UserIntegrationRepository;
import com.ironside.challengesphere.service.HevyIntegrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
public class HevyIntegrationServiceImpl implements HevyIntegrationService {
    private static final Logger logger = LogManager.getLogger(HevyIntegrationServiceImpl.class);
    private static final String PROVIDER = "HEVY";
    private static final String HEVY_USER_INFO_URL = "https://api.hevyapp.com/v1/user/info";
    private static final String HEVY_WORKOUTS_URL = "https://api.hevyapp.com/v1/workouts";
    private static final int WORKOUT_LIMIT = 10;

    private final UserIntegrationRepository repository;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HevyIntegrationServiceImpl(UserIntegrationRepository repository,
                                      RestClient.Builder restClientBuilder,
                                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public HevyConnectionResponse connect(Long userId, HevyConnectRequest request) throws ChallengeSphereException {
        JsonNode userInfo = fetchUserInfo(request.apiKey());
        Instant now = Instant.now();
        UserIntegration integration = repository.findByUserIdAndProvider(userId, PROVIDER).orElseGet(UserIntegration::new);
        integration.setUserId(userId);
        integration.setProvider(PROVIDER);
        integration.setExternalUserId(text(userInfo, "id", "user_id"));
        integration.setExternalUsername(text(userInfo, "username", "handle"));
        integration.setDisplayName(text(userInfo, "name", "display_name", "username"));
        integration.setAccessToken(request.apiKey());
        integration.setConnectedAt(integration.getConnectedAt() != null ? integration.getConnectedAt() : Timestamp.from(now));
        integration.setUpdatedAt(Timestamp.from(now));
        repository.save(integration);
        return toConnectionResponse(integration, readSummary(integration.getSnapshotJson()));
    }

    @Override
    public HevyConnectionResponse getConnection(Long userId) throws ChallengeSphereException {
        UserIntegration integration = repository.findByUserIdAndProvider(userId, PROVIDER)
                .orElseThrow(() -> new ChallengeSphereException("Hevy is not connected yet.", HttpStatus.NOT_FOUND, logger));
        return toConnectionResponse(integration, readSummary(integration.getSnapshotJson()));
    }

    @Override
    @Transactional
    public HevyConnectionResponse sync(Long userId) throws ChallengeSphereException {
        UserIntegration integration = repository.findByUserIdAndProvider(userId, PROVIDER)
                .orElseThrow(() -> new ChallengeSphereException("Connect Hevy before syncing workouts.", HttpStatus.BAD_REQUEST, logger));
        JsonNode workouts = fetchWorkouts(integration.getAccessToken());
        HevyWorkoutSummary summary = toWorkoutSummary(workouts, Instant.now());
        integration.setSnapshotJson(writeSummary(summary));
        integration.setLastSyncedAt(Timestamp.from(Instant.parse(summary.syncedAt())));
        integration.setUpdatedAt(Timestamp.from(Instant.now()));
        repository.save(integration);
        return toConnectionResponse(integration, summary);
    }

    @Override
    @Transactional
    public void disconnect(Long userId) {
        repository.deleteByUserIdAndProvider(userId, PROVIDER);
    }

    public static HevyConnectionResponse toConnectionResponse(UserIntegration integration, HevyWorkoutSummary summary) {
        return new HevyConnectionResponse(
                PROVIDER,
                integration.getExternalUserId(),
                integration.getExternalUsername(),
                integration.getDisplayName(),
                integration.getConnectedAt() != null ? integration.getConnectedAt().toInstant().toString() : null,
                integration.getUpdatedAt() != null ? integration.getUpdatedAt().toInstant().toString() : null,
                integration.getLastSyncedAt() != null ? integration.getLastSyncedAt().toInstant().toString() : null,
                summary
        );
    }

    public static HevyWorkoutSummary toWorkoutSummary(JsonNode rawWorkouts, Instant syncedAt) {
        JsonNode workoutArray = rawWorkouts != null && rawWorkouts.has("workouts") ? rawWorkouts.get("workouts") : rawWorkouts;
        List<HevyWorkoutSummaryItem> items = new ArrayList<>();
        if (workoutArray != null && workoutArray.isArray()) {
            for (JsonNode workout : workoutArray) {
                items.add(toWorkoutItem(workout));
            }
        }
        int exerciseCount = items.stream().mapToInt(HevyWorkoutSummaryItem::exerciseCount).sum();
        int setCount = items.stream().mapToInt(HevyWorkoutSummaryItem::setCount).sum();
        double totalVolumeKg = items.stream().mapToDouble(HevyWorkoutSummaryItem::volumeKg).sum();
        int totalDurationSeconds = items.stream().mapToInt(HevyWorkoutSummaryItem::durationSeconds).sum();
        return new HevyWorkoutSummary(
                items.size(),
                exerciseCount,
                setCount,
                roundVolume(totalVolumeKg),
                totalDurationSeconds,
                syncedAt.toString(),
                items
        );
    }

    private JsonNode fetchUserInfo(String apiKey) throws ChallengeSphereException {
        try {
            return restClient.get()
                    .uri(URI.create(HEVY_USER_INFO_URL))
                    .header("api-key", apiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new ChallengeSphereException("Hevy did not accept this API key. Check that you have Hevy Pro and a valid developer key.", HttpStatus.BAD_GATEWAY, logger, e);
        }
    }

    private JsonNode fetchWorkouts(String apiKey) throws ChallengeSphereException {
        try {
            return restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(HEVY_WORKOUTS_URL)
                            .queryParam("page", 1)
                            .queryParam("pageSize", WORKOUT_LIMIT)
                            .build()
                            .toUri())
                    .header("api-key", apiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new ChallengeSphereException("Could not sync Hevy workouts right now.", HttpStatus.BAD_GATEWAY, logger, e);
        }
    }

    private HevyWorkoutSummary readSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, HevyWorkoutSummary.class);
        } catch (JsonProcessingException e) {
            logger.warn("Could not read Hevy snapshot JSON: {}", e.getMessage());
            return null;
        }
    }

    private String writeSummary(HevyWorkoutSummary summary) throws ChallengeSphereException {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new ChallengeSphereException("Could not save Hevy workout snapshot.", HttpStatus.INTERNAL_SERVER_ERROR, logger, e);
        }
    }

    private static HevyWorkoutSummaryItem toWorkoutItem(JsonNode workout) {
        JsonNode exercises = array(workout, "exercises");
        int exerciseCount = exercises.size();
        int setCount = 0;
        double volumeKg = 0;
        List<String> exerciseNames = new ArrayList<>();

        for (JsonNode exercise : exercises) {
            String exerciseName = text(exercise, "title", "name");
            if (exerciseNames.size() < 3 && exerciseName != null && !exerciseName.isBlank()) {
                exerciseNames.add(exerciseName);
            }
            JsonNode sets = array(exercise, "sets");
            setCount += sets.size();
            for (JsonNode set : sets) {
                volumeKg += decimal(set, "weight_kg", "weight") * decimal(set, "reps");
            }
        }

        return new HevyWorkoutSummaryItem(
                text(workout, "id", "workout_id"),
                text(workout, "title", "name"),
                text(workout, "start_time", "started_at"),
                durationSeconds(workout),
                exerciseCount,
                setCount,
                roundVolume(volumeKg),
                String.join(", ", exerciseNames)
        );
    }

    private static JsonNode array(JsonNode node, String fieldName) {
        JsonNode value = node != null ? node.get(fieldName) : null;
        return value != null && value.isArray() ? value : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static double decimal(JsonNode node, String... fields) {
        if (node == null) {
            return 0;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isNumber()) {
                return value.asDouble();
            }
        }
        return 0;
    }

    private static int durationSeconds(JsonNode workout) {
        double explicitDuration = decimal(workout, "duration_seconds");
        if (explicitDuration > 0) {
            return (int) Math.round(explicitDuration);
        }
        String start = text(workout, "start_time", "started_at");
        String end = text(workout, "end_time", "ended_at");
        if (start == null || end == null) {
            return 0;
        }
        try {
            return (int) Duration.between(Instant.parse(start), Instant.parse(end)).toSeconds();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    private static double roundVolume(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
