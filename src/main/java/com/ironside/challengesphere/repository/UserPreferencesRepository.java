package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
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
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Repository
public class UserPreferencesRepository extends AbstractDynamoRepository<UserPreferences> {

    private static final String ENTITY_TYPE = "USER_PREFERENCES";

    private final TransactionalDynamoWriter writer;

    public UserPreferencesRepository(DynamoDomainStore store, TransactionalDynamoWriter writer) {
        super(store, ENTITY_TYPE, UserPreferences.class);
        this.writer = writer;
    }

    @Override
    public UserPreferences save(UserPreferences preferences) {
        if (preferences == null) {
            throw new IllegalArgumentException("User preferences must not be null");
        }
        requireText(preferences.getUserId(), "userId");
        boolean creating = preferences.getId() == null || preferences.getId().isBlank();
        if (creating) {
            preferences.setId(store.nextId());
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (preferences.getCreatedAt() == null) {
            preferences.setCreatedAt(now);
        }
        preferences.setUpdatedAt(now);

        Optional<TransactionalDynamoWriter.Versioned<UserPreferences>> previous =
                writer.loadVersioned(ENTITY_TYPE, preferences.getId(), UserPreferences.class);
        writer.save(
                ENTITY_TYPE,
                preferences.getId(),
                preferences,
                allProjections(preferences),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::allProjections).orElseGet(List::of),
                userUniqueProjection(preferences),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::userUniqueProjection).orElseGet(List::of),
                creating,
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return preferences;
    }

    @Override
    public void delete(UserPreferences preferences) {
        if (preferences == null || preferences.getId() == null || preferences.getId().isBlank()) {
            return;
        }
        writer.loadVersioned(ENTITY_TYPE, preferences.getId(), UserPreferences.class)
                .ifPresent(current -> writer.delete(
                        ENTITY_TYPE,
                        preferences.getId(),
                        allProjections(current.value()),
                        userUniqueProjection(current.value()),
                        current.version()
                ));
    }

    public Optional<UserPreferences> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return store.queryAll(
                        DynamoAccessPatterns.ownerPk("USER", userId),
                        "PREFERENCES#",
                        1,
                        true,
                        UserPreferences.class,
                        true
                ).stream()
                .findFirst();
    }

    public List<UserPreferences> findByUserIdIn(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<UserPreferences> preferences = new ArrayList<>();
        userIds.stream().distinct().forEach(userId -> findByUserId(userId).ifPresent(preferences::add));
        return List.copyOf(preferences);
    }

    public boolean existsByUserId(String userId) {
        return findByUserId(userId).isPresent();
    }

    public void updatePushNotificationsEnabled(String userId, Boolean value) {
        update(userId, preferences -> preferences.setPushNotificationsEnabled(value));
    }

    public void updateEmailNotificationsEnabled(String userId, Boolean value) {
        update(userId, preferences -> preferences.setEmailNotificationsEnabled(value));
    }

    public void updateNotifyOnGlory(String userId, Boolean value) {
        update(userId, preferences -> preferences.setNotifyOnGlory(value));
    }

    public void updateNotifyOnComment(String userId, Boolean value) {
        update(userId, preferences -> preferences.setNotifyOnComment(value));
    }

    public void updateNotifyOnFollow(String userId, Boolean value) {
        update(userId, preferences -> preferences.setNotifyOnFollow(value));
    }

    public void updateNotifyOnChallengeInvite(String userId, Boolean value) {
        update(userId, preferences -> preferences.setNotifyOnChallengeInvite(value));
    }

    public void updateNotifyOnChallengeUpdate(String userId, Boolean value) {
        update(userId, preferences -> preferences.setNotifyOnChallengeUpdate(value));
    }

    public void updateNotifyOnMotivation(String userId, Boolean value) {
        update(userId, preferences -> preferences.setNotifyOnMotivation(value));
    }

    public void updatePrivateProfile(String userId, Boolean value) {
        update(userId, preferences -> preferences.setPrivateProfile(value));
    }

    public void updatePrivateSubmissions(String userId, Boolean value) {
        update(userId, preferences -> preferences.setPrivateSubmissions(value));
    }

    public void updateShowOnLeaderboard(String userId, Boolean value) {
        update(userId, preferences -> preferences.setShowOnLeaderboard(value));
    }

    public void updateAllowChallengeInvites(String userId, Boolean value) {
        update(userId, preferences -> preferences.setAllowChallengeInvites(value));
    }

    public void updateDarkMode(String userId, Boolean value) {
        update(userId, preferences -> preferences.setDarkMode(value));
    }

    public void updateLanguage(String userId, String value) {
        update(userId, preferences -> preferences.setLanguage(value));
    }

    @Override
    protected String id(UserPreferences entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(UserPreferences entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(UserPreferences preferences) {
        return List.of(UserDynamoKeys.projection(
                DynamoAccessPatterns.ownerPk("USER", preferences.getUserId()),
                "PREFERENCES#" + preferences.getId()
        ));
    }

    private List<DynamoProjection> userUniqueProjection(UserPreferences preferences) {
        return List.of(UserDynamoKeys.projection(
                UserDynamoKeys.uniquePk(ENTITY_TYPE, "USER_ID", preferences.getUserId()),
                UserDynamoKeys.LOOKUP
        ));
    }

    private void update(String userId, Consumer<UserPreferences> mutation) {
        Optional<UserPreferences> lookup = findByUserId(userId);
        if (lookup.isEmpty()) {
            return;
        }
        String preferencesId = lookup.get().getId();
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<TransactionalDynamoWriter.Versioned<UserPreferences>> existing =
                    writer.loadVersioned(ENTITY_TYPE, preferencesId, UserPreferences.class);
            if (existing.isEmpty()) {
                return;
            }
            TransactionalDynamoWriter.Versioned<UserPreferences> versioned = existing.get();
            UserPreferences preferences = versioned.value();
            List<DynamoProjection> previousProjections = allProjections(preferences);
            List<DynamoProjection> previousUniqueProjections = userUniqueProjection(preferences);
            mutation.accept(preferences);
            preferences.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            try {
                writer.save(
                        ENTITY_TYPE,
                        preferencesId,
                        preferences,
                        allProjections(preferences),
                        previousProjections,
                        userUniqueProjection(preferences),
                        previousUniqueProjections,
                        false,
                        versioned.version()
                );
                return;
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        throw lastConflict;
    }
}
