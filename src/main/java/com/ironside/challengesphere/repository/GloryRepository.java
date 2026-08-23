package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Repository
public class GloryRepository extends AbstractDynamoRepository<Glory> {

    private final UserBlockRepository userBlockRepository;
    private final SubmissionRepository submissionRepository;
    private final LeaderboardEntryRepository leaderboardEntryRepository;
    private final TransactionalDynamoWriter writer;

    public GloryRepository(
            DynamoDomainStore store,
            UserBlockRepository userBlockRepository,
            SubmissionRepository submissionRepository,
            LeaderboardEntryRepository leaderboardEntryRepository,
            TransactionalDynamoWriter writer
    ) {
        super(store, "GLORY", Glory.class);
        this.userBlockRepository = userBlockRepository;
        this.submissionRepository = submissionRepository;
        this.leaderboardEntryRepository = leaderboardEntryRepository;
        this.writer = writer;
    }

    @Override
    public Glory save(Glory glory) {
        if (glory.getCreatedAt() == null) glory.setCreatedAt(Timestamp.from(Instant.now()));
        if (glory.getId() == null || glory.getId().isBlank()) glory.setId(store.nextId());
        Optional<TransactionalDynamoWriter.Versioned<Glory>> previous =
                writer.loadVersioned(entityType(), glory.getId(), Glory.class);
        writer.save(
                entityType(),
                glory.getId(),
                glory,
                allProjections(glory),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::allProjections).orElseGet(List::of),
                pairProjection(glory),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::pairProjection).orElseGet(List::of),
                previous.isEmpty(),
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return glory;
    }

    @Override
    public void delete(Glory glory) {
        if (glory == null || glory.getId() == null || glory.getId().isBlank()) return;
        writer.loadVersioned(entityType(), glory.getId(), Glory.class).ifPresent(current -> writer.delete(
                entityType(),
                glory.getId(),
                allProjections(current.value()),
                pairProjection(current.value()),
                current.version()
        ));
    }

    public Optional<Glory> findBySubmissionIdAndUserId(String submissionId, String userId) {
        return queryProjectionPartition(
                pairPartition(submissionId, userId),
                "LOOKUP",
                true
        ).stream().findFirst();
    }

    public GloryTransition toggleGlory(String submissionId, String userId) {
        return toggle(submissionId, userId, ToggleMode.GLORY);
    }

    public GloryTransition toggleUnglory(String submissionId, String userId) {
        return toggle(submissionId, userId, ToggleMode.UNGLORY);
    }

    public List<Glory> findActiveBySubmissionId(String submissionId) {
        return queryProjectionPartition("SUBMISSION#" + requireText(submissionId, "submissionId"), "GLORY#ACTIVE#", false);
    }

    public DynamoPage<Glory> findActivePageBySubmissionId(
            String submissionId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return queryProjectionPage(
                "SUBMISSION#" + requireText(submissionId, "submissionId"),
                "GLORY#ACTIVE#",
                limit,
                exclusiveStartKey,
                false
        );
    }

    public List<Glory> findBySubmissionId(String submissionId) {
        return queryProjectionPartition(
                "SUBMISSION#" + requireText(submissionId, "submissionId"),
                "GLORY#USER#",
                false
        );
    }

    public Page<Glory> findActiveBySubmissionId(String submissionId, Pageable pageable) {
        return page(findActiveBySubmissionId(submissionId), pageable);
    }

    public Page<Glory> findActiveBySubmissionIdForViewer(String submissionId, String viewerId, Pageable pageable) {
        List<Glory> visible = findActiveBySubmissionId(submissionId).stream()
                .filter(glory -> viewerId == null || !userBlockRepository.existsActiveBlockBetween(viewerId, glory.getUserId()))
                .toList();
        return page(visible, pageable);
    }

    public Long countActiveBySubmissionId(String submissionId) {
        return (long) findActiveBySubmissionId(submissionId).size();
    }

    public List<Glory> findActiveByUserId(String userId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"), "GLORY#ACTIVE#", false);
    }

    public List<Glory> findByUserId(String userId) {
        return queryProjectionPartition(
                "USER#" + requireText(userId, "userId"),
                "GLORY#ALL#",
                false
        );
    }

    public boolean existsBySubmissionIdAndUserId(String submissionId, String userId) {
        return findBySubmissionIdAndUserId(submissionId, userId)
                .filter(glory -> Boolean.TRUE.equals(glory.getActive())).isPresent();
    }

    public boolean existsUngloriedBySubmissionIdAndUserId(String submissionId, String userId) {
        return findBySubmissionIdAndUserId(submissionId, userId)
                .filter(glory -> Boolean.TRUE.equals(glory.getUngloried())).isPresent();
    }

    /**
     * Resolves the current viewer's reaction state for a bounded submission page
     * with fixed-key BatchGet requests instead of one Query per submission.
     */
    public Map<String, ReactionState> findReactionStates(
            Collection<String> submissionIds,
            String userId
    ) {
        if (submissionIds == null || submissionIds.isEmpty()
                || userId == null || userId.isBlank()) {
            return Map.of();
        }
        List<DynamoDbKey> keys = submissionIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .map(id -> new DynamoDbKey(pairPartition(id, userId), "LOOKUP"))
                .toList();
        return store.batchGetProjections(keys, Glory.class, true).stream()
                .filter(glory -> glory.getSubmissionId() != null)
                .collect(Collectors.toUnmodifiableMap(
                        Glory::getSubmissionId,
                        glory -> new ReactionState(
                                Boolean.TRUE.equals(glory.getActive()),
                                Boolean.TRUE.equals(glory.getUngloried())
                        ),
                        (left, right) -> right
                ));
    }

    public Long countUngloriedBySubmissionId(String submissionId) {
        return (long) queryProjectionPartition(
                "SUBMISSION#" + requireText(submissionId, "submissionId"),
                "GLORY#UNGLORIED#",
                false
        ).size();
    }

    private Page<Glory> page(List<Glory> content, Pageable pageable) {
        int start = Math.toIntExact(Math.min(pageable.getOffset(), content.size()));
        int end = Math.min(start + pageable.getPageSize(), content.size());
        return new PageImpl<>(content.subList(start, end), pageable, content.size());
    }

    @Override
    protected String id(Glory entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(Glory entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(Glory entity) {
        String created = entity.getCreatedAt() == null ? entity.getId() : entity.getCreatedAt().toInstant().toString();
        String suffix = created + '#' + entity.getId();
        List<DynamoProjection> result = new ArrayList<>();
        if (entity.getSubmissionId() != null && entity.getUserId() != null) {
            result.add(DynamoProjection.of(
                    "SUBMISSION#" + entity.getSubmissionId(),
                    "GLORY#USER#" + entity.getUserId() + '#' + entity.getId()
            ));
            result.add(DynamoProjection.of(
                    "USER#" + entity.getUserId(),
                    "GLORY#ALL#" + suffix
            ));
        }
        if (Boolean.TRUE.equals(entity.getActive())) {
            if (entity.getSubmissionId() != null) result.add(DynamoProjection.of("SUBMISSION#" + entity.getSubmissionId(), "GLORY#ACTIVE#" + suffix));
            if (entity.getUserId() != null) result.add(DynamoProjection.of("USER#" + entity.getUserId(), "GLORY#ACTIVE#" + suffix));
        }
        if (Boolean.TRUE.equals(entity.getUngloried()) && entity.getSubmissionId() != null) {
            result.add(DynamoProjection.of("SUBMISSION#" + entity.getSubmissionId(), "GLORY#UNGLORIED#" + suffix));
        }
        return result;
    }

    private GloryTransition toggle(String submissionId, String userId, ToggleMode mode) {
        requireText(submissionId, "submissionId");
        requireText(userId, "userId");
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            Optional<TransactionalDynamoWriter.Versioned<Submission>> versionedSubmission =
                    submissionRepository.loadVersionedForGloryMutation(submissionId);
            if (versionedSubmission.isEmpty()) {
                throw new IllegalArgumentException("Submission not found: " + submissionId);
            }

            Optional<Glory> lookup = findBySubmissionIdAndUserId(submissionId, userId);
            Glory current;
            List<DynamoProjection> previousProjections;
            List<DynamoProjection> previousPairProjection;
            boolean creating;
            Long expectedVersion;
            boolean wasActive;
            if (lookup.isEmpty()) {
                current = new Glory();
                current.setId(store.nextId());
                current.setSubmissionId(submissionId);
                current.setUserId(userId);
                current.setCreatedAt(Timestamp.from(Instant.now()));
                current.setActive(mode == ToggleMode.GLORY);
                current.setUngloried(mode == ToggleMode.UNGLORY);
                previousProjections = List.of();
                previousPairProjection = List.of();
                creating = true;
                expectedVersion = null;
                wasActive = false;
            } else {
                Glory existing = lookup.get();
                Optional<TransactionalDynamoWriter.Versioned<Glory>> versioned =
                        writer.loadVersioned(entityType(), existing.getId(), Glory.class);
                if (versioned.isEmpty()) continue;
                current = versioned.get().value();
                previousProjections = allProjections(current);
                previousPairProjection = pairProjection(current);
                creating = false;
                expectedVersion = versioned.get().version();
                wasActive = Boolean.TRUE.equals(current.getActive());
                if (mode == ToggleMode.GLORY) {
                    current.setActive(!wasActive);
                    if (!wasActive) current.setUngloried(false);
                } else {
                    boolean wasUngloried = Boolean.TRUE.equals(current.getUngloried());
                    current.setUngloried(!wasUngloried);
                    if (!wasUngloried) current.setActive(false);
                }
            }

            boolean active = Boolean.TRUE.equals(current.getActive());
            int activeDelta = Boolean.compare(active, wasActive);
            try {
                List<TransactionalDynamoWriter.AggregateWrite> writes = new ArrayList<>();
                writes.add(new TransactionalDynamoWriter.AggregateWrite(
                        entityType(), current.getId(), current,
                        allProjections(current), previousProjections,
                        pairProjection(current), previousPairProjection,
                        creating, expectedVersion
                ));
                if (activeDelta != 0) {
                    writes.add(submissionRepository.gloryCountAggregateWrite(
                            versionedSubmission.get(), activeDelta
                    ));
                    Submission submission = versionedSubmission.get().value();
                    writes.add(leaderboardEntryRepository.gloryCountAggregateWrite(
                            submission.getChallengeId(), submission.getUserId(), activeDelta
                    ));
                }
                writer.saveAtomically(writes);
                return new GloryTransition(
                        current,
                        versionedSubmission.get().value(),
                        active,
                        Boolean.TRUE.equals(current.getUngloried()),
                        activeDelta
                );
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        throw new DataIntegrityViolationException("Concurrent glory toggle did not converge", lastConflict);
    }

    private List<DynamoProjection> pairProjection(Glory glory) {
        return List.of(DynamoProjection.of(
                pairPartition(glory.getSubmissionId(), glory.getUserId()),
                "LOOKUP"
        ));
    }

    private static String pairPartition(String submissionId, String userId) {
        return "GLORY#SUBMISSION#" + requireText(submissionId, "submissionId")
                + "#USER#" + requireText(userId, "userId");
    }

    private enum ToggleMode { GLORY, UNGLORY }

    public record GloryTransition(
            Glory glory,
            Submission submission,
            boolean active,
            boolean ungloried,
            int activeDelta
    ) { }

    public record ReactionState(boolean gloried, boolean ungloried) { }
}
