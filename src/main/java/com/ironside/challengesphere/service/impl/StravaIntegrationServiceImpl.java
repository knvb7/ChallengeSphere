package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.StravaConnectRequest;
import com.ironside.challengesphere.api.response.StravaActivitySummary;
import com.ironside.challengesphere.api.response.StravaActivitySummaryItem;
import com.ironside.challengesphere.api.response.StravaAuthorizeUrlResponse;
import com.ironside.challengesphere.api.response.StravaConnectionResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.integration.strava.StravaAthlete;
import com.ironside.challengesphere.integration.strava.StravaDetailedActivity;
import com.ironside.challengesphere.integration.strava.StravaTokenResponse;
import com.ironside.challengesphere.model.UserIntegration;
import com.ironside.challengesphere.repository.UserIntegrationRepository;
import com.ironside.challengesphere.service.StravaIntegrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class StravaIntegrationServiceImpl implements StravaIntegrationService {
    private static final Logger logger = LogManager.getLogger(StravaIntegrationServiceImpl.class);
    private static final String PROVIDER = "STRAVA";
    private static final String STRAVA_AUTH_URL = "https://www.strava.com/oauth/mobile/authorize";
    private static final String STRAVA_TOKEN_URL = "https://www.strava.com/oauth/token";
    private static final String STRAVA_ACTIVITIES_URL = "https://www.strava.com/api/v3/athlete/activities";
    private static final String STRAVA_SCOPE = "read,activity:read";
    private static final int ACTIVITY_LIMIT = 30;

    private final UserIntegrationRepository repository;
    private final StravaOAuthStateSigner stateSigner;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;

    public StravaIntegrationServiceImpl(UserIntegrationRepository repository,
                                        StravaOAuthStateSigner stateSigner,
                                        RestClient.Builder restClientBuilder,
                                        ObjectMapper objectMapper,
                                        @Value("${app.strava.client-id:}") String clientId,
                                        @Value("${app.strava.client-secret:}") String clientSecret) {
        this.repository = repository;
        this.stateSigner = stateSigner;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public StravaAuthorizeUrlResponse createAuthorizationUrl(Long userId, String redirectUri) throws ChallengeSphereException {
        requireConfigured();
        String state = stateSigner.sign(userId, Instant.now());
        String authorizationUrl = UriComponentsBuilder.fromUriString(STRAVA_AUTH_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("approval_prompt", "auto")
                .queryParam("scope", STRAVA_SCOPE)
                .queryParam("state", state)
                .build()
                .toUriString();
        return new StravaAuthorizeUrlResponse(authorizationUrl, state);
    }

    @Override
    @Transactional
    public StravaConnectionResponse connect(Long userId, StravaConnectRequest request) throws ChallengeSphereException {
        requireConfigured();
        if (!stateSigner.verify(request.state(), userId, Instant.now())) {
            throw new ChallengeSphereException("Strava authorization expired. Please try connecting again.", HttpStatus.BAD_REQUEST, logger);
        }

        StravaTokenResponse token = exchangeCode(request.code(), request.redirectUri());
        UserIntegration integration = repository.findByUserIdAndProvider(userId, PROVIDER).orElseGet(UserIntegration::new);
        applyToken(integration, userId, token, request.scope());
        repository.save(integration);
        return toConnectionResponse(token, request.scope(), integration.getConnectedAt().toInstant(), null);
    }

    @Override
    public StravaConnectionResponse getConnection(Long userId) throws ChallengeSphereException {
        UserIntegration integration = repository.findByUserIdAndProvider(userId, PROVIDER)
                .orElseThrow(() -> new ChallengeSphereException("Strava is not connected yet.", HttpStatus.NOT_FOUND, logger));
        return toConnectionResponse(integration);
    }

    @Override
    @Transactional
    public StravaConnectionResponse sync(Long userId) throws ChallengeSphereException {
        requireConfigured();
        UserIntegration integration = repository.findByUserIdAndProvider(userId, PROVIDER)
                .orElseThrow(() -> new ChallengeSphereException("Connect Strava before syncing activities.", HttpStatus.BAD_REQUEST, logger));
        String accessToken = ensureAccessToken(integration);
        List<StravaDetailedActivity> activities = fetchActivities(accessToken);
        StravaActivitySummary summary = toActivitySummary(activities, Instant.now());
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

    public static StravaConnectionResponse toConnectionResponse(StravaTokenResponse token,
                                                                String scopes,
                                                                Instant connectedAt,
                                                                StravaActivitySummary snapshot) {
        StravaAthlete athlete = token.getAthlete();
        String firstname = athlete != null ? nullToBlank(athlete.getFirstname()) : "";
        String lastname = athlete != null ? nullToBlank(athlete.getLastname()) : "";
        String displayName = (firstname + " " + lastname).trim();
        if (displayName.isBlank()) {
            displayName = athlete != null ? athlete.getUsername() : "";
        }
        return new StravaConnectionResponse(
                PROVIDER,
                athlete != null && athlete.getId() != null ? athlete.getId().toString() : "",
                athlete != null ? athlete.getUsername() : "",
                displayName,
                athlete != null && athlete.getProfile() != null ? athlete.getProfile() : athlete != null ? athlete.getProfileMedium() : null,
                scopes,
                connectedAt.toString(),
                connectedAt.toString(),
                snapshot != null ? snapshot.syncedAt() : null,
                snapshot
        );
    }

    public static StravaActivitySummary toActivitySummary(List<StravaDetailedActivity> activities, Instant syncedAt) {
        List<StravaActivitySummaryItem> items = activities.stream()
                .map(StravaIntegrationServiceImpl::toActivityItem)
                .toList();
        double totalDistanceKm = items.stream().mapToDouble(StravaActivitySummaryItem::distanceKm).sum();
        int totalMovingSeconds = items.stream().mapToInt(StravaActivitySummaryItem::movingSeconds).sum();
        return new StravaActivitySummary(
                items.size(),
                roundDistance(totalDistanceKm),
                totalMovingSeconds,
                syncedAt.toString(),
                items
        );
    }

    private StravaTokenResponse exchangeCode(String code, String redirectUri) throws ChallengeSphereException {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", redirectUri);
        try {
            return restClient.post()
                    .uri(URI.create(STRAVA_TOKEN_URL))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(StravaTokenResponse.class);
        } catch (Exception e) {
            throw new ChallengeSphereException("Strava did not accept this authorization code.", HttpStatus.BAD_GATEWAY, logger, e);
        }
    }

    private String ensureAccessToken(UserIntegration integration) throws ChallengeSphereException {
        Instant expiresAt = integration.getAccessTokenExpiresAt() != null
                ? integration.getAccessTokenExpiresAt().toInstant()
                : Instant.EPOCH;
        if (integration.getAccessToken() != null && expiresAt.isAfter(Instant.now().plusSeconds(60))) {
            return integration.getAccessToken();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", integration.getRefreshToken());
        try {
            StravaTokenResponse token = restClient.post()
                    .uri(URI.create(STRAVA_TOKEN_URL))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(StravaTokenResponse.class);
            integration.setAccessToken(token.getAccessToken());
            integration.setRefreshToken(token.getRefreshToken());
            integration.setAccessTokenExpiresAt(Timestamp.from(Instant.ofEpochSecond(token.getExpiresAt())));
            integration.setUpdatedAt(Timestamp.from(Instant.now()));
            repository.save(integration);
            return token.getAccessToken();
        } catch (Exception e) {
            throw new ChallengeSphereException("Could not refresh Strava access. Please reconnect Strava.", HttpStatus.BAD_GATEWAY, logger, e);
        }
    }

    private List<StravaDetailedActivity> fetchActivities(String accessToken) throws ChallengeSphereException {
        try {
            return restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(STRAVA_ACTIVITIES_URL)
                            .queryParam("per_page", ACTIVITY_LIMIT)
                            .queryParam("page", 1)
                            .build()
                            .toUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<StravaDetailedActivity>>() {});
        } catch (Exception e) {
            throw new ChallengeSphereException("Could not sync Strava activities right now.", HttpStatus.BAD_GATEWAY, logger, e);
        }
    }

    private void applyToken(UserIntegration integration, Long userId, StravaTokenResponse token, String scopes) {
        Instant now = Instant.now();
        StravaAthlete athlete = token.getAthlete();
        integration.setUserId(userId);
        integration.setProvider(PROVIDER);
        integration.setExternalUserId(athlete != null && athlete.getId() != null ? athlete.getId().toString() : null);
        integration.setExternalUsername(athlete != null ? athlete.getUsername() : null);
        integration.setDisplayName(displayNameFor(athlete));
        integration.setAvatarUrl(athlete != null && athlete.getProfile() != null ? athlete.getProfile() : athlete != null ? athlete.getProfileMedium() : null);
        integration.setAccessToken(token.getAccessToken());
        integration.setRefreshToken(token.getRefreshToken());
        integration.setAccessTokenExpiresAt(Timestamp.from(Instant.ofEpochSecond(token.getExpiresAt())));
        integration.setScopes(scopes != null && !scopes.isBlank() ? scopes : token.getScope());
        integration.setConnectedAt(integration.getConnectedAt() != null ? integration.getConnectedAt() : Timestamp.from(now));
        integration.setUpdatedAt(Timestamp.from(now));
    }

    private StravaConnectionResponse toConnectionResponse(UserIntegration integration) {
        return toConnectionResponse(integration, readSummary(integration.getSnapshotJson()));
    }

    private StravaConnectionResponse toConnectionResponse(UserIntegration integration, StravaActivitySummary summary) {
        return new StravaConnectionResponse(
                PROVIDER,
                integration.getExternalUserId(),
                integration.getExternalUsername(),
                integration.getDisplayName(),
                integration.getAvatarUrl(),
                integration.getScopes(),
                integration.getConnectedAt() != null ? integration.getConnectedAt().toInstant().toString() : null,
                integration.getUpdatedAt() != null ? integration.getUpdatedAt().toInstant().toString() : null,
                integration.getLastSyncedAt() != null ? integration.getLastSyncedAt().toInstant().toString() : null,
                summary
        );
    }

    private StravaActivitySummary readSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, StravaActivitySummary.class);
        } catch (JsonProcessingException e) {
            logger.warn("Could not read Strava snapshot JSON: {}", e.getMessage());
            return null;
        }
    }

    private String writeSummary(StravaActivitySummary summary) throws ChallengeSphereException {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new ChallengeSphereException("Could not save Strava activity snapshot.", HttpStatus.INTERNAL_SERVER_ERROR, logger, e);
        }
    }

    private void requireConfigured() throws ChallengeSphereException {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new ChallengeSphereException("Strava API credentials are not configured on the server.", HttpStatus.SERVICE_UNAVAILABLE, logger);
        }
    }

    private static StravaActivitySummaryItem toActivityItem(StravaDetailedActivity activity) {
        return new StravaActivitySummaryItem(
                activity.getId() != null ? activity.getId().toString() : "",
                activity.getName(),
                sportTypeFor(activity),
                roundDistance((activity.getDistance() == null ? 0.0 : activity.getDistance()) / 1000.0),
                activity.getMovingTime() == null ? 0 : activity.getMovingTime(),
                activity.getStartDate(),
                activity.getExternalId()
        );
    }

    private static String sportTypeFor(StravaDetailedActivity activity) {
        String sportType = activity.getSportType() != null ? activity.getSportType() : activity.getType();
        if (sportType == null || sportType.isBlank()) {
            return "Activity";
        }
        return sportType.substring(0, 1).toUpperCase(Locale.ROOT) + sportType.substring(1);
    }

    private static double roundDistance(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String displayNameFor(StravaAthlete athlete) {
        if (athlete == null) {
            return null;
        }
        String displayName = (nullToBlank(athlete.getFirstname()) + " " + nullToBlank(athlete.getLastname())).trim();
        return displayName.isBlank() ? athlete.getUsername() : displayName;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
