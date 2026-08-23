package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class PushDeviceTokenRepository extends AbstractDynamoRepository<PushDeviceToken> {

    private static final String ENTITY_TYPE = "PUSH_DEVICE_TOKEN";
    private static final String REMINDER_PARTITION = "REMINDER_ENABLED";

    private final TransactionalDynamoWriter writer;
    private final UserRepository userRepository;
    private final UserPreferencesRepository preferencesRepository;

    public PushDeviceTokenRepository(
            DynamoDomainStore store,
            TransactionalDynamoWriter writer,
            UserRepository userRepository,
            UserPreferencesRepository preferencesRepository
    ) {
        super(store, ENTITY_TYPE, PushDeviceToken.class);
        this.writer = writer;
        this.userRepository = userRepository;
        this.preferencesRepository = preferencesRepository;
    }

    @Override
    public PushDeviceToken save(PushDeviceToken token) {
        if (token == null) {
            throw new IllegalArgumentException("Push device token must not be null");
        }
        requireText(token.getUserId(), "userId");
        requireText(token.getExpoPushToken(), "expoPushToken");
        token.setExpoPushToken(token.getExpoPushToken().strip());
        boolean creating = token.getId() == null || token.getId().isBlank();
        if (creating) {
            token.setId(store.nextId());
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (token.getCreatedAt() == null) {
            token.setCreatedAt(now);
        }
        if (token.getLastSeenAt() == null) {
            token.setLastSeenAt(now);
        }
        token.setUpdatedAt(now);
        if (token.getEnabled() == null) {
            token.setEnabled(true);
        }

        Optional<TransactionalDynamoWriter.Versioned<PushDeviceToken>> previous =
                writer.loadVersioned(ENTITY_TYPE, token.getId(), PushDeviceToken.class);
        writer.save(
                ENTITY_TYPE,
                token.getId(),
                token,
                allProjections(token),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::allProjections).orElseGet(List::of),
                tokenUniqueProjection(token),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::tokenUniqueProjection).orElseGet(List::of),
                creating,
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return token;
    }

    @Override
    public void delete(PushDeviceToken token) {
        if (token == null || token.getId() == null || token.getId().isBlank()) {
            return;
        }
        writer.loadVersioned(ENTITY_TYPE, token.getId(), PushDeviceToken.class)
                .ifPresent(current -> writer.delete(
                        ENTITY_TYPE,
                        token.getId(),
                        allProjections(current.value()),
                        tokenUniqueProjection(current.value()),
                        current.version()
                ));
    }

    public Optional<PushDeviceToken> findByExpoPushToken(String expoPushToken) {
        if (expoPushToken == null || expoPushToken.isBlank()) {
            return Optional.empty();
        }
        return store.queryAll(
                        UserDynamoKeys.uniquePkExact(
                                ENTITY_TYPE,
                                "EXPO_TOKEN",
                                expoPushToken.strip()
                        ),
                        UserDynamoKeys.LOOKUP,
                        1,
                        true,
                        PushDeviceToken.class,
                        true
                ).stream()
                .findFirst();
    }

    public List<PushDeviceToken> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return store.queryAll(
                UserDynamoKeys.ownerCollectionPk("PUSH_TOKENS", userId),
                "TOKEN#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true,
                PushDeviceToken.class,
                true
        );
    }

    public List<PushDeviceToken> findEligibleByUserIdAndType(String userId, String type) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        User user = userRepository.findById(userId).orElse(null);
        UserPreferences preferences = preferencesRepository.findByUserId(userId).orElse(null);
        if (!isGloballyEligible(user, preferences)
                || !isTypeEligible(preferences, type)) {
            return List.of();
        }
        return store.queryAll(
                UserDynamoKeys.ownerCollectionPk("PUSH_TOKENS_ENABLED", userId),
                "TOKEN#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true,
                PushDeviceToken.class,
                true
        );
    }

    public List<PushDeviceToken> findEnabledReminderTokens() {
        List<PushDeviceToken> candidates = store.queryAll(
                UserDynamoKeys.collection(ENTITY_TYPE, REMINDER_PARTITION),
                "USER#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true,
                PushDeviceToken.class,
                true
        );
        return candidates.stream()
                .filter(token -> {
                    User user = userRepository.findById(token.getUserId()).orElse(null);
                    UserPreferences preferences = preferencesRepository
                            .findByUserId(token.getUserId())
                            .orElse(null);
                    return isGloballyEligible(user, preferences)
                            && enabled(preferences == null ? null : preferences.getNotifyOnMotivation());
                })
                .sorted(Comparator.comparing(PushDeviceToken::getUserId)
                        .thenComparing(
                                PushDeviceToken::getLastSeenAt,
                                Comparator.nullsLast(Comparator.reverseOrder())
                        ))
                .toList();
    }

    public int disableToken(String userId, String expoPushToken) {
        Optional<PushDeviceToken> existing = findByExpoPushToken(expoPushToken)
                .filter(token -> userId != null && userId.equals(token.getUserId()));
        if (existing.isEmpty()) {
            return 0;
        }
        return disable(existing.get().getId(), userId, expoPushToken);
    }

    public int disableTokenByExpoPushToken(String expoPushToken) {
        Optional<PushDeviceToken> existing = findByExpoPushToken(expoPushToken);
        if (existing.isEmpty()) {
            return 0;
        }
        return disable(existing.get().getId(), null, expoPushToken);
    }

    @Override
    protected String id(PushDeviceToken entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(PushDeviceToken entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(PushDeviceToken token) {
        List<DynamoProjection> projections = new ArrayList<>(3);
        String userSortKey = tokenSortKey(token);
        projections.add(UserDynamoKeys.projection(
                UserDynamoKeys.ownerCollectionPk("PUSH_TOKENS", token.getUserId()),
                userSortKey
        ));
        if (Boolean.TRUE.equals(token.getEnabled())) {
            projections.add(UserDynamoKeys.projection(
                    UserDynamoKeys.ownerCollectionPk("PUSH_TOKENS_ENABLED", token.getUserId()),
                    userSortKey
            ));
            projections.add(UserDynamoKeys.projection(
                    UserDynamoKeys.collection(ENTITY_TYPE, REMINDER_PARTITION),
                    "USER#" + token.getUserId() + '#'
                            + invertedTimestamp(token.getLastSeenAt()) + '#' + token.getId()
            ));
        }
        return projections;
    }

    private List<DynamoProjection> tokenUniqueProjection(PushDeviceToken token) {
        return List.of(UserDynamoKeys.projection(
                UserDynamoKeys.uniquePkExact(
                        ENTITY_TYPE,
                        "EXPO_TOKEN",
                        token.getExpoPushToken().strip()
                ),
                UserDynamoKeys.LOOKUP
        ));
    }

    private String tokenSortKey(PushDeviceToken token) {
        return "TOKEN#" + invertedTimestamp(token.getLastSeenAt()) + '#' + token.getId();
    }

    private String invertedTimestamp(Timestamp timestamp) {
        long millis = timestamp == null ? 0L : Math.max(0L, timestamp.getTime());
        return String.format(Locale.ROOT, "%019d", Long.MAX_VALUE - millis);
    }

    private boolean isGloballyEligible(User user, UserPreferences preferences) {
        if (user == null || !Boolean.TRUE.equals(user.getActive()) || user.getDeletedAt() != null) {
            return false;
        }
        Integer notificationMask = user.getNotifications();
        if (notificationMask != null && (notificationMask & 1) != 1) {
            return false;
        }
        return enabled(preferences == null ? null : preferences.getPushNotificationsEnabled());
    }

    private boolean isTypeEligible(UserPreferences preferences, String type) {
        String normalizedType = type == null ? "" : type.strip().toUpperCase(Locale.ROOT);
        if (preferences == null) {
            return true;
        }
        return switch (normalizedType) {
            case "FOLLOW" -> enabled(preferences.getNotifyOnFollow());
            case "GLORY" -> enabled(preferences.getNotifyOnGlory());
            case "COMMENT" -> enabled(preferences.getNotifyOnComment());
            case "CHALLENGE_INVITE", "INVITE" -> enabled(preferences.getNotifyOnChallengeInvite())
                    && enabled(preferences.getAllowChallengeInvites());
            case "SUBMISSION", "SUBMISSION_UPDATE", "CHALLENGE_UPDATE" ->
                    enabled(preferences.getNotifyOnChallengeUpdate());
            case "MOMENTUM_MILESTONE" -> enabled(preferences.getNotifyOnMotivation());
            default -> true;
        };
    }

    private boolean enabled(Boolean value) {
        return value == null || Boolean.TRUE.equals(value);
    }

    private int disable(String id, String expectedUserId, String expectedExpoPushToken) {
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<TransactionalDynamoWriter.Versioned<PushDeviceToken>> existing =
                    writer.loadVersioned(ENTITY_TYPE, id, PushDeviceToken.class);
            if (existing.isEmpty()) {
                return 0;
            }
            TransactionalDynamoWriter.Versioned<PushDeviceToken> versioned = existing.get();
            PushDeviceToken token = versioned.value();
            if (expectedUserId != null && !expectedUserId.equals(token.getUserId())) {
                return 0;
            }
            if (expectedExpoPushToken == null
                    || !expectedExpoPushToken.strip().equals(token.getExpoPushToken())) {
                return 0;
            }
            List<DynamoProjection> previousProjections = allProjections(token);
            List<DynamoProjection> previousUniqueProjections = tokenUniqueProjection(token);
            token.setEnabled(false);
            token.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            try {
                writer.save(
                        ENTITY_TYPE,
                        id,
                        token,
                        allProjections(token),
                        previousProjections,
                        tokenUniqueProjection(token),
                        previousUniqueProjections,
                        false,
                        versioned.version()
                );
                return 1;
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        throw lastConflict;
    }
}
