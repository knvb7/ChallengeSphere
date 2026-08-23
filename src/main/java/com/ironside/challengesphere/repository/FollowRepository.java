package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Repository
public class FollowRepository extends AbstractDynamoRepository<Follow> {

    private static final String ENTITY_TYPE = "FOLLOW";

    private final TransactionalDynamoWriter writer;

    public FollowRepository(DynamoDomainStore store, TransactionalDynamoWriter writer) {
        super(store, ENTITY_TYPE, Follow.class);
        this.writer = writer;
    }

    @Override
    public Follow save(Follow follow) {
        if (follow == null) {
            throw new IllegalArgumentException("Follow must not be null");
        }
        boolean creating = follow.getId() == null || follow.getId().isBlank();
        if (creating) {
            follow.setId(store.nextId());
        }
        requireText(follow.getFollowerId(), "followerId");
        requireText(follow.getFollowingId(), "followingId");
        if (follow.getCreatedAt() == null) {
            follow.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        }
        if (follow.getActive() == null) {
            follow.setActive(true);
        }

        Optional<TransactionalDynamoWriter.Versioned<Follow>> previous =
                writer.loadVersioned(ENTITY_TYPE, follow.getId(), Follow.class);
        writer.save(
                ENTITY_TYPE,
                follow.getId(),
                follow,
                allProjections(follow),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::allProjections).orElseGet(List::of),
                pairProjection(follow),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::pairProjection).orElseGet(List::of),
                creating,
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return follow;
    }

    @Override
    public void delete(Follow follow) {
        if (follow == null || follow.getId() == null || follow.getId().isBlank()) {
            return;
        }
        writer.loadVersioned(ENTITY_TYPE, follow.getId(), Follow.class).ifPresent(current -> writer.delete(
                ENTITY_TYPE,
                follow.getId(),
                allProjections(current.value()),
                pairProjection(current.value()),
                current.version()
        ));
    }

    public boolean existsActiveFollow(String viewerId, String targetUserId) {
        if (viewerId == null || targetUserId == null) {
            return false;
        }
        if (viewerId.equals(targetUserId)) {
            return true;
        }
        return existsByFollowerIdAndFollowingIdAndActive(viewerId, targetUserId);
    }

    public boolean existsActiveMutualFollow(String firstUserId, String secondUserId) {
        if (firstUserId == null || secondUserId == null) {
            return false;
        }
        if (firstUserId.equals(secondUserId)) {
            return true;
        }
        return existsByFollowerIdAndFollowingIdAndActive(firstUserId, secondUserId)
                && existsByFollowerIdAndFollowingIdAndActive(secondUserId, firstUserId);
    }

    public List<Follow> findByFollowerId(String followerId) {
        return queryEdges("FOLLOWS", followerId);
    }

    public List<Follow> findByFollowingId(String followingId) {
        return queryEdges("FOLLOWERS", followingId);
    }

    public Optional<Follow> findByFollowerIdAndFollowingId(String followerId, String followingId) {
        if (followerId == null || followingId == null) {
            return Optional.empty();
        }
        return store.queryAll(
                        UserDynamoKeys.exactPairPk("FOLLOW", followerId, followingId),
                        UserDynamoKeys.LOOKUP,
                        1,
                        true,
                        Follow.class,
                        true
                ).stream()
                .findFirst();
    }

    public boolean existsByFollowerIdAndFollowingIdAndActive(String followerId, String followingId) {
        return findByFollowerIdAndFollowingId(followerId, followingId)
                .map(Follow::getActive)
                .map(Boolean.TRUE::equals)
                .orElse(false);
    }

    /** Batch-checks only the candidate pairs examined by one discovery page. */
    public Set<String> findActiveFollowedCandidateIds(
            String followerId,
            Collection<String> candidateUserIds
    ) {
        if (followerId == null || followerId.isBlank()
                || candidateUserIds == null || candidateUserIds.isEmpty()) {
            return Set.of();
        }
        Set<String> candidates = candidateUserIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !followerId.equals(value))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        List<DynamoDbKey> keys = candidates.stream()
                .map(candidate -> UserDynamoKeys.projection(
                        UserDynamoKeys.exactPairPk("FOLLOW", followerId, candidate),
                        UserDynamoKeys.LOOKUP
                ).key())
                .toList();
        return store.batchGetProjections(keys, Follow.class, true).stream()
                .filter(follow -> Boolean.TRUE.equals(follow.getActive()))
                .filter(follow -> followerId.equals(follow.getFollowerId()))
                .map(Follow::getFollowingId)
                .filter(candidates::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Batch-checks which candidate users actively follow the supplied user. */
    public Set<String> findActiveFollowerCandidateIds(
            String followingId,
            Collection<String> candidateFollowerIds
    ) {
        if (followingId == null || followingId.isBlank()
                || candidateFollowerIds == null || candidateFollowerIds.isEmpty()) {
            return Set.of();
        }
        Set<String> candidates = candidateFollowerIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !followingId.equals(value))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        List<DynamoDbKey> keys = candidates.stream()
                .map(candidate -> UserDynamoKeys.projection(
                        UserDynamoKeys.exactPairPk("FOLLOW", candidate, followingId),
                        UserDynamoKeys.LOOKUP
                ).key())
                .toList();
        return store.batchGetProjections(keys, Follow.class, true).stream()
                .filter(follow -> Boolean.TRUE.equals(follow.getActive()))
                .filter(follow -> followingId.equals(follow.getFollowingId()))
                .map(Follow::getFollowerId)
                .filter(candidates::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public void deleteByFollowerIdAndFollowingId(String followerId, String followingId) {
        findByFollowerIdAndFollowingId(followerId, followingId).ifPresent(this::delete);
    }

    public List<Follow> findActiveFollowsByFollowerId(String followerId) {
        return queryEdges("ACTIVE_FOLLOWS", followerId);
    }

    public List<Follow> findActiveFollowsByFollowingId(String followingId) {
        return queryEdges("ACTIVE_FOLLOWERS", followingId);
    }

    public DynamoPage<Follow> findActiveFollowersPage(
            String followingId,
            int limit,
            String continuationSortKey
    ) {
        if (followingId == null || followingId.isBlank()) {
            return new DynamoPage<>(List.of(), Map.of());
        }
        String partition = UserDynamoKeys.ownerCollectionPk("ACTIVE_FOLLOWERS", followingId);
        return findActiveFollowersPage(
                followingId,
                limit,
                FeedTimelineRepository.continuationKey(partition, continuationSortKey)
        );
    }

    public DynamoPage<Follow> findActiveFollowersPage(
            String followingId,
            int limit,
            Map<String, AttributeValue> lastEvaluatedKey
    ) {
        if (followingId == null || followingId.isBlank()) {
            return new DynamoPage<>(List.of(), Map.of());
        }
        String partition = UserDynamoKeys.ownerCollectionPk("ACTIVE_FOLLOWERS", followingId);
        return store.query(
                partition,
                "EDGE#",
                limit,
                lastEvaluatedKey,
                true,
                Follow.class,
                true
        );
    }

    public DynamoPage<Follow> findActiveFollowingPage(
            String followerId,
            int limit,
            String continuationSortKey
    ) {
        if (followerId == null || followerId.isBlank()) {
            return new DynamoPage<>(List.of(), Map.of());
        }
        String partition = UserDynamoKeys.ownerCollectionPk("ACTIVE_FOLLOWS", followerId);
        return findActiveFollowingPage(
                followerId,
                limit,
                FeedTimelineRepository.continuationKey(partition, continuationSortKey)
        );
    }

    public DynamoPage<Follow> findActiveFollowingPage(
            String followerId,
            int limit,
            Map<String, AttributeValue> lastEvaluatedKey
    ) {
        if (followerId == null || followerId.isBlank()) {
            return new DynamoPage<>(List.of(), Map.of());
        }
        String partition = UserDynamoKeys.ownerCollectionPk("ACTIVE_FOLLOWS", followerId);
        return store.query(
                partition,
                "EDGE#",
                limit,
                lastEvaluatedKey,
                true,
                Follow.class,
                true
        );
    }

    public Map<String, AttributeValue> activeFollowerContinuationKey(
            String followingId,
            Follow follow
    ) {
        return activeEdgeContinuationKey(
                "ACTIVE_FOLLOWERS",
                followingId,
                follow == null ? null : follow.getFollowerId(),
                follow
        );
    }

    public Map<String, AttributeValue> activeFollowingContinuationKey(
            String followerId,
            Follow follow
    ) {
        return activeEdgeContinuationKey(
                "ACTIVE_FOLLOWS",
                followerId,
                follow == null ? null : follow.getFollowingId(),
                follow
        );
    }

    private static Map<String, AttributeValue> activeEdgeContinuationKey(
            String collection,
            String ownerId,
            String targetId,
            Follow follow
    ) {
        if (follow == null || follow.getId() == null || follow.getId().isBlank()) {
            throw new IllegalArgumentException("follow must have an ID");
        }
        return DynamoDbKeys.attributeKey(
                UserDynamoKeys.ownerCollectionPk(collection, requireText(ownerId, "ownerId")),
                "EDGE#" + requireText(targetId, "targetId") + '#' + follow.getId()
        );
    }

    public List<Follow> findPendingTimelineBackfills(int limit) {
        return store.query(
                FeedTimelineRepository.PENDING_FOLLOW_PK,
                FeedTimelineRepository.PENDING_ITEM_PREFIX,
                limit,
                Map.of(),
                true,
                Follow.class,
                true
        ).items();
    }

    public Long countActiveFollowingByFollowerId(String followerId) {
        return (long) findActiveFollowsByFollowerId(followerId).size();
    }

    public Long countActiveFollowersByFollowingId(String followingId) {
        return (long) findActiveFollowsByFollowingId(followingId).size();
    }

    public void updateFollowActiveStatus(String id, Boolean active) {
        updateActive(id, active);
    }

    public void unfollowUser(String followerId, String followingId) {
        findByFollowerIdAndFollowingId(followerId, followingId)
                .ifPresent(follow -> updateActive(follow.getId(), false));
    }

    @Override
    protected String id(Follow entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(Follow entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(Follow follow) {
        List<DynamoProjection> projections = new ArrayList<>(5);
        projections.add(edge("FOLLOWS", follow.getFollowerId(), follow.getFollowingId(), follow.getId()));
        projections.add(edge("FOLLOWERS", follow.getFollowingId(), follow.getFollowerId(), follow.getId()));
        if (Boolean.TRUE.equals(follow.getActive())) {
            projections.add(edge(
                    "ACTIVE_FOLLOWS",
                    follow.getFollowerId(),
                    follow.getFollowingId(),
                    follow.getId()
            ));
            projections.add(edge(
                    "ACTIVE_FOLLOWERS",
                    follow.getFollowingId(),
                    follow.getFollowerId(),
                    follow.getId()
            ));
            if (follow.getFeedTimelineBackfillCompletedAt() == null) {
                projections.add(DynamoProjection.of(
                        FeedTimelineRepository.PENDING_FOLLOW_PK,
                        FeedTimelineRepository.pendingSortKey(
                                follow.getCreatedAt() == null
                                        ? null
                                        : follow.getCreatedAt().toInstant(),
                                follow.getId()
                        )
                ));
            }
        }
        return projections;
    }

    private List<DynamoProjection> pairProjection(Follow follow) {
        return List.of(UserDynamoKeys.projection(
                UserDynamoKeys.exactPairPk(
                        "FOLLOW",
                        follow.getFollowerId(),
                        follow.getFollowingId()
                ),
                UserDynamoKeys.LOOKUP
        ));
    }

    List<DynamoProjection> transitionProjections(Follow follow) {
        return allProjections(follow);
    }

    List<DynamoProjection> transitionUniqueProjections(Follow follow) {
        return pairProjection(follow);
    }

    private DynamoProjection edge(String collection, String owner, String target, String id) {
        return UserDynamoKeys.projection(
                UserDynamoKeys.ownerCollectionPk(collection, owner),
                "EDGE#" + target + '#' + id
        );
    }

    private List<Follow> queryEdges(String collection, String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return List.of();
        }
        return store.queryAll(
                UserDynamoKeys.ownerCollectionPk(collection, ownerId),
                "EDGE#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true,
                Follow.class,
                true
        );
    }

    private void updateActive(String id, Boolean active) {
        if (id == null || id.isBlank()) {
            return;
        }
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<TransactionalDynamoWriter.Versioned<Follow>> existing =
                    writer.loadVersioned(ENTITY_TYPE, id, Follow.class);
            if (existing.isEmpty()) {
                return;
            }
            TransactionalDynamoWriter.Versioned<Follow> versioned = existing.get();
            Follow follow = versioned.value();
            List<DynamoProjection> previousProjections = allProjections(follow);
            List<DynamoProjection> previousUniqueProjections = pairProjection(follow);
            follow.setActive(active);
            if (Boolean.TRUE.equals(active)) {
                follow.setFeedTimelineBackfillCursor(null);
                follow.setFeedTimelineBackfillCompletedAt(null);
            }
            try {
                writer.save(
                        ENTITY_TYPE,
                        id,
                        follow,
                        allProjections(follow),
                        previousProjections,
                        pairProjection(follow),
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

    public void updateTimelineBackfillProgress(
            String followId,
            String continuationSortKey,
            boolean completed
    ) {
        if (followId == null || followId.isBlank()) {
            return;
        }
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            Optional<TransactionalDynamoWriter.Versioned<Follow>> existing =
                    writer.loadVersioned(ENTITY_TYPE, followId, Follow.class);
            if (existing.isEmpty()) {
                return;
            }
            Follow follow = existing.get().value();
            List<DynamoProjection> previousProjections = allProjections(follow);
            List<DynamoProjection> previousUniqueProjections = pairProjection(follow);
            follow.setFeedTimelineBackfillCursor(completed ? null : continuationSortKey);
            follow.setFeedTimelineBackfillCompletedAt(
                    completed ? new Timestamp(System.currentTimeMillis()) : null
            );
            try {
                writer.save(
                        ENTITY_TYPE,
                        followId,
                        follow,
                        allProjections(follow),
                        previousProjections,
                        pairProjection(follow),
                        previousUniqueProjections,
                        false,
                        existing.get().version()
                );
                return;
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        throw new DataIntegrityViolationException(
                "Concurrent follow timeline progress update did not converge for " + followId,
                lastConflict
        );
    }
}
