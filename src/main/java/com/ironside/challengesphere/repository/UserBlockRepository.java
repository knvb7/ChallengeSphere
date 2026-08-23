package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserBlock;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
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
public class UserBlockRepository extends AbstractDynamoRepository<UserBlock> {

    private static final String ENTITY_TYPE = "USER_BLOCK";

    private final TransactionalDynamoWriter writer;

    public UserBlockRepository(DynamoDomainStore store, TransactionalDynamoWriter writer) {
        super(store, ENTITY_TYPE, UserBlock.class);
        this.writer = writer;
    }

    @Override
    public UserBlock save(UserBlock block) {
        if (block == null) {
            throw new IllegalArgumentException("User block must not be null");
        }
        boolean creating = block.getId() == null || block.getId().isBlank();
        if (creating) {
            block.setId(store.nextId());
        }
        requireText(block.getBlockerUserId(), "blockerUserId");
        requireText(block.getBlockedUserId(), "blockedUserId");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (block.getCreatedAt() == null) {
            block.setCreatedAt(now);
        }
        block.setUpdatedAt(now);
        if (block.getActive() == null) {
            block.setActive(true);
        }

        Optional<TransactionalDynamoWriter.Versioned<UserBlock>> previous =
                writer.loadVersioned(ENTITY_TYPE, block.getId(), UserBlock.class);
        writer.save(
                ENTITY_TYPE,
                block.getId(),
                block,
                allProjections(block),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::allProjections).orElseGet(List::of),
                pairProjection(block),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::pairProjection).orElseGet(List::of),
                creating,
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return block;
    }

    @Override
    public void delete(UserBlock block) {
        if (block == null || block.getId() == null || block.getId().isBlank()) {
            return;
        }
        writer.loadVersioned(ENTITY_TYPE, block.getId(), UserBlock.class).ifPresent(current -> writer.delete(
                ENTITY_TYPE,
                block.getId(),
                allProjections(current.value()),
                pairProjection(current.value()),
                current.version()
        ));
    }

    public Optional<UserBlock> findByBlockerUserIdAndBlockedUserId(
            String blockerUserId,
            String blockedUserId
    ) {
        if (blockerUserId == null || blockedUserId == null) {
            return Optional.empty();
        }
        return store.queryAll(
                        UserDynamoKeys.exactPairPk("BLOCK", blockerUserId, blockedUserId),
                        UserDynamoKeys.LOOKUP,
                        1,
                        true,
                        UserBlock.class,
                        true
                ).stream()
                .findFirst();
    }

    public List<UserBlock> findActiveByBlockerUserId(String blockerUserId) {
        List<UserBlock> blocks = queryEdges("ACTIVE_BLOCKS", blockerUserId);
        return blocks.stream()
                .sorted((left, right) -> compareCreatedDescending(left.getCreatedAt(), right.getCreatedAt()))
                .toList();
    }

    public DynamoPage<UserBlock> findActiveByBlockerUserIdPage(
            String blockerUserId,
            int limit,
            Map<String, AttributeValue> lastEvaluatedKey
    ) {
        if (blockerUserId == null || blockerUserId.isBlank()) {
            return new DynamoPage<>(List.of(), Map.of());
        }
        return store.query(
                UserDynamoKeys.ownerCollectionPk("ACTIVE_BLOCKS", blockerUserId),
                "EDGE#",
                limit,
                lastEvaluatedKey,
                true,
                UserBlock.class,
                true
        );
    }

    public List<UserBlock> findByBlockerUserId(String blockerUserId) {
        return queryEdges("BLOCKS", blockerUserId);
    }

    public List<UserBlock> findByBlockedUserId(String blockedUserId) {
        return queryEdges("BLOCKED_BY", blockedUserId);
    }

    public boolean existsActiveBlock(String blockerUserId, String blockedUserId) {
        return findByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)
                .map(UserBlock::getActive)
                .map(Boolean.TRUE::equals)
                .orElse(false);
    }

    public boolean existsActiveBlockBetween(String firstUserId, String secondUserId) {
        if (firstUserId == null || secondUserId == null || firstUserId.equals(secondUserId)) {
            return false;
        }
        return existsActiveBlock(firstUserId, secondUserId)
                || existsActiveBlock(secondUserId, firstUserId);
    }

    public List<String> findActiveBlockedCounterpartIds(String viewerUserId) {
        if (viewerUserId == null || viewerUserId.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> counterparts = new LinkedHashSet<>();
        queryEdges("ACTIVE_BLOCKS", viewerUserId).stream()
                .map(UserBlock::getBlockedUserId)
                .forEach(counterparts::add);
        queryEdges("ACTIVE_BLOCKED_BY", viewerUserId).stream()
                .map(UserBlock::getBlockerUserId)
                .forEach(counterparts::add);
        return List.copyOf(counterparts);
    }

    /** Batch-checks both block directions for only the authors in one feed candidate page. */
    public Set<String> findActiveBlockedCounterpartIds(
            String viewerUserId,
            Collection<String> candidateUserIds
    ) {
        if (viewerUserId == null || viewerUserId.isBlank()
                || candidateUserIds == null || candidateUserIds.isEmpty()) {
            return Set.of();
        }
        Set<String> candidates = candidateUserIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !viewerUserId.equals(value))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<DynamoDbKey> keys = candidates.stream()
                .flatMap(candidate -> java.util.stream.Stream.of(
                        UserDynamoKeys.projection(
                                UserDynamoKeys.exactPairPk("BLOCK", viewerUserId, candidate),
                                UserDynamoKeys.LOOKUP
                        ).key(),
                        UserDynamoKeys.projection(
                                UserDynamoKeys.exactPairPk("BLOCK", candidate, viewerUserId),
                                UserDynamoKeys.LOOKUP
                        ).key()
                ))
                .toList();
        LinkedHashSet<String> blocked = new LinkedHashSet<>();
        store.batchGetProjections(keys, UserBlock.class, true).stream()
                .filter(block -> Boolean.TRUE.equals(block.getActive()))
                .forEach(block -> {
                    if (viewerUserId.equals(block.getBlockerUserId())
                            && candidates.contains(block.getBlockedUserId())) {
                        blocked.add(block.getBlockedUserId());
                    } else if (viewerUserId.equals(block.getBlockedUserId())
                            && candidates.contains(block.getBlockerUserId())) {
                        blocked.add(block.getBlockerUserId());
                    }
                });
        return Set.copyOf(blocked);
    }

    @Override
    protected String id(UserBlock entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(UserBlock entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(UserBlock block) {
        List<DynamoProjection> projections = new ArrayList<>(4);
        projections.add(edge("BLOCKS", block.getBlockerUserId(), block.getBlockedUserId(), block.getId()));
        projections.add(edge(
                "BLOCKED_BY",
                block.getBlockedUserId(),
                block.getBlockerUserId(),
                block.getId()
        ));
        if (Boolean.TRUE.equals(block.getActive())) {
            projections.add(edge(
                    "ACTIVE_BLOCKS",
                    block.getBlockerUserId(),
                    block.getBlockedUserId(),
                    block.getId()
            ));
            projections.add(edge(
                    "ACTIVE_BLOCKED_BY",
                    block.getBlockedUserId(),
                    block.getBlockerUserId(),
                    block.getId()
            ));
        }
        return projections;
    }

    private List<DynamoProjection> pairProjection(UserBlock block) {
        return List.of(UserDynamoKeys.projection(
                UserDynamoKeys.exactPairPk(
                        "BLOCK",
                        block.getBlockerUserId(),
                        block.getBlockedUserId()
                ),
                UserDynamoKeys.LOOKUP
        ));
    }

    private DynamoProjection edge(String collection, String owner, String target, String id) {
        return UserDynamoKeys.projection(
                UserDynamoKeys.ownerCollectionPk(collection, owner),
                "EDGE#" + target + '#' + id
        );
    }

    private List<UserBlock> queryEdges(String collection, String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return List.of();
        }
        return store.queryAll(
                UserDynamoKeys.ownerCollectionPk(collection, ownerId),
                "EDGE#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true,
                UserBlock.class,
                true
        );
    }

    private int compareCreatedDescending(Timestamp left, Timestamp right) {
        long leftMillis = left == null ? 0L : left.getTime();
        long rightMillis = right == null ? 0L : right.getTime();
        return Long.compare(rightMillis, leftMillis);
    }
}
