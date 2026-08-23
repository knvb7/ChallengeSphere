package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Comment;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Repository
public class CommentRepository extends AbstractDynamoRepository<Comment> {

    private static final int MAX_ATOMIC_RETRIES = 6;

    private final UserBlockRepository userBlockRepository;
    private final SubmissionRepository submissionRepository;
    private final TransactionalDynamoWriter writer;

    public CommentRepository(
            DynamoDomainStore store,
            UserBlockRepository userBlockRepository,
            SubmissionRepository submissionRepository,
            TransactionalDynamoWriter writer
    ) {
        super(store, "COMMENT", Comment.class);
        this.userBlockRepository = userBlockRepository;
        this.submissionRepository = submissionRepository;
        this.writer = writer;
    }

    @Override
    public Comment save(Comment comment) {
        prepareForSave(comment);
        return super.save(comment);
    }

    public Comment createWithSubmissionCommentCount(Comment comment) {
        if (comment == null) {
            throw new IllegalArgumentException("comment must not be null");
        }
        if (comment.getId() != null && !comment.getId().isBlank()) {
            throw new IllegalArgumentException("new comment must not already have an id");
        }
        if (!Boolean.TRUE.equals(comment.getActive())) {
            throw new IllegalArgumentException("new comment must be active");
        }

        comment.setId(store.nextId());
        prepareForSave(comment);
        TransactionalDynamoWriter.AggregateWrite commentWrite =
                new TransactionalDynamoWriter.AggregateWrite(
                        entityType(),
                        comment.getId(),
                        comment,
                        allProjections(comment),
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        null
                );

        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < MAX_ATOMIC_RETRIES; attempt++) {
            Optional<TransactionalDynamoWriter.Versioned<Submission>>
                    submission = submissionRepository.loadVersionedForCommentMutation(
                            requireText(comment.getSubmissionId(), "submissionId")
                    );
            if (submission.isEmpty()) {
                throw new IllegalArgumentException(
                        "Submission not found: " + comment.getSubmissionId()
                );
            }
            try {
                writer.saveAtomically(List.of(
                        commentWrite,
                        submissionRepository.commentCountAggregateWrite(submission.get(), 1)
                ));
                return comment;
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        throw new DataIntegrityViolationException(
                "Concurrent comment creation did not converge for submission "
                        + comment.getSubmissionId(),
                lastConflict
        );
    }

    public SoftDeleteResult softDeleteWithSubmissionCommentCount(
            String commentId,
            String requestingUserId
    ) {
        requireText(requestingUserId, "requestingUserId");
        return softDeleteWithSubmissionCommentCount(commentId, requestingUserId, true);
    }

    public SoftDeleteResult softDeleteForModerationWithSubmissionCommentCount(String commentId) {
        return softDeleteWithSubmissionCommentCount(commentId, null, false);
    }

    private SoftDeleteResult softDeleteWithSubmissionCommentCount(
            String commentId,
            String requestingUserId,
            boolean requireOwnership
    ) {
        requireText(commentId, "commentId");
        DataIntegrityViolationException lastConflict = null;

        for (int attempt = 0; attempt < MAX_ATOMIC_RETRIES; attempt++) {
            Optional<TransactionalDynamoWriter.Versioned<Comment>> versionedComment =
                    writer.loadVersioned(entityType(), commentId, Comment.class);
            if (versionedComment.isEmpty()) {
                return new SoftDeleteResult(null, SoftDeleteStatus.NOT_FOUND);
            }

            Comment latest = versionedComment.get().value();
            if (requireOwnership && !requestingUserId.equals(latest.getUserId())) {
                return new SoftDeleteResult(latest, SoftDeleteStatus.NOT_AUTHORIZED);
            }
            if (!Boolean.TRUE.equals(latest.getActive())) {
                return new SoftDeleteResult(latest, SoftDeleteStatus.ALREADY_DELETED);
            }

            Optional<TransactionalDynamoWriter.Versioned<Submission>>
                    submission = submissionRepository.loadVersionedForCommentMutation(
                            requireText(latest.getSubmissionId(), "submissionId")
                    );
            if (submission.isEmpty()) {
                throw new IllegalArgumentException(
                        "Submission not found: " + latest.getSubmissionId()
                );
            }

            List<DynamoProjection> previousCommentProjections = allProjections(latest);
            latest.setActive(false);
            prepareForSave(latest);
            TransactionalDynamoWriter.AggregateWrite commentWrite =
                    new TransactionalDynamoWriter.AggregateWrite(
                            entityType(),
                            latest.getId(),
                            latest,
                            allProjections(latest),
                            previousCommentProjections,
                            List.of(),
                            List.of(),
                            false,
                            versionedComment.get().version()
                    );
            try {
                writer.saveAtomically(List.of(
                        commentWrite,
                        submissionRepository.commentCountAggregateWrite(submission.get(), -1)
                ));
                return new SoftDeleteResult(latest, SoftDeleteStatus.DELETED);
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        throw new DataIntegrityViolationException(
                "Concurrent comment deletion did not converge for " + commentId,
                lastConflict
        );
    }

    private void prepareForSave(Comment comment) {
        if (comment == null) {
            throw new IllegalArgumentException("comment must not be null");
        }
        Timestamp now = Timestamp.from(Instant.now());
        if (comment.getCreatedAt() == null) comment.setCreatedAt(now);
        comment.setUpdatedAt(now);
    }

    public Page<Comment> findActiveCommentsBySubmissionId(String submissionId, Pageable pageable) {
        return page(activeForSubmission(submissionId), pageable);
    }

    public DynamoPage<Comment> findActiveCommentsPageBySubmissionId(
            String submissionId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return queryProjectionPage(
                "SUBMISSION#" + requireText(submissionId, "submissionId"),
                "COMMENT#ACTIVE#",
                limit,
                exclusiveStartKey,
                false
        );
    }

    public Page<Comment> findActiveCommentsBySubmissionIdForViewer(
            String submissionId,
            String viewerId,
            Pageable pageable
    ) {
        List<Comment> visible = activeForSubmission(submissionId).stream()
                .filter(comment -> viewerId == null
                        || !userBlockRepository.existsActiveBlockBetween(viewerId, comment.getUserId()))
                .toList();
        return page(visible, pageable);
    }

    public Long countActiveCommentsBySubmissionId(String submissionId) {
        return (long) activeForSubmission(submissionId).size();
    }

    public Optional<Comment> findActiveById(String id) {
        return findById(id).filter(comment -> Boolean.TRUE.equals(comment.getActive()));
    }

    public List<Comment> findBySubmissionId(String submissionId) {
        return queryProjectionPartition("SUBMISSION#" + requireText(submissionId, "submissionId"), "COMMENT#ALL#", false);
    }

    public List<Comment> findByUserId(String userId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"), "COMMENT#ALL#", false);
    }

    public List<Comment> findBySubmissionIdAndUserId(String submissionId, String userId) {
        return findBySubmissionId(submissionId).stream().filter(comment -> userId.equals(comment.getUserId())).toList();
    }

    public List<Comment> findAllActiveCommentsOrderByCreatedAtDesc() {
        return queryProjectionPartition("COMMENT#ACTIVE", "COMMENT#", false);
    }

    public List<Comment> findActiveCommentsBySubmissionIdOrderByCreatedAtAsc(String submissionId) {
        List<Comment> comments = new ArrayList<>(activeForSubmission(submissionId));
        comments.sort(Comparator.comparing(Comment::getCreatedAt).thenComparing(Comment::getId));
        return List.copyOf(comments);
    }

    public List<Comment> findActiveCommentsByUserIdOrderByCreatedAtDesc(String userId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"), "COMMENT#ACTIVE#", false);
    }

    /** Prefix-only search, matching the DynamoDB migration contract. */
    public List<Comment> searchCommentsByContent(String searchTerm) {
        String normalized = DynamoAccessPatterns.normalized(searchTerm);
        List<Comment> matches = new ArrayList<>();
        for (int shard = 0; shard < DynamoAccessPatterns.SEARCH_SHARDS; shard++) {
            matches.addAll(queryProjectionPartition(
                    DynamoAccessPatterns.searchPk("COMMENT", shard),
                    normalized,
                    true
            ));
        }
        return matches.stream().distinct().toList();
    }

    public Long countActiveCommentsByUserId(String userId) {
        return (long) findActiveCommentsByUserIdOrderByCreatedAtDesc(userId).size();
    }

    public void deleteBySubmissionId(String submissionId) {
        findBySubmissionId(submissionId).forEach(this::delete);
    }

    private List<Comment> activeForSubmission(String submissionId) {
        return queryProjectionPartition(
                "SUBMISSION#" + requireText(submissionId, "submissionId"),
                "COMMENT#ACTIVE#",
                false
        );
    }

    private Page<Comment> page(List<Comment> content, Pageable pageable) {
        int start = Math.toIntExact(Math.min(pageable.getOffset(), content.size()));
        int end = Math.min(start + pageable.getPageSize(), content.size());
        return new PageImpl<>(content.subList(start, end), pageable, content.size());
    }

    @Override
    protected String id(Comment entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(Comment entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(Comment entity) {
        String created = entity.getCreatedAt() == null ? entity.getId() : entity.getCreatedAt().toInstant().toString();
        String suffix = created + '#' + entity.getId();
        List<DynamoProjection> result = new ArrayList<>();
        if (entity.getSubmissionId() != null) {
            result.add(DynamoProjection.of("SUBMISSION#" + entity.getSubmissionId(), "COMMENT#ALL#" + suffix));
        }
        if (entity.getUserId() != null) {
            result.add(DynamoProjection.of("USER#" + entity.getUserId(), "COMMENT#ALL#" + suffix));
        }
        if (Boolean.TRUE.equals(entity.getActive())) {
            if (entity.getSubmissionId() != null) result.add(DynamoProjection.of("SUBMISSION#" + entity.getSubmissionId(), "COMMENT#ACTIVE#" + suffix));
            if (entity.getUserId() != null) result.add(DynamoProjection.of("USER#" + entity.getUserId(), "COMMENT#ACTIVE#" + suffix));
            result.add(DynamoProjection.of("COMMENT#ACTIVE", "COMMENT#" + suffix));
            if (entity.getContent() != null && !entity.getContent().isBlank()) {
                int shard = DynamoAccessPatterns.stableShard(entity.getId(), DynamoAccessPatterns.SEARCH_SHARDS);
                result.add(DynamoProjection.of(
                        DynamoAccessPatterns.searchPk("COMMENT", shard),
                        DynamoAccessPatterns.searchSk(entity.getContent(), entity.getId())
                ));
            }
        }
        return result;
    }

    public enum SoftDeleteStatus {
        DELETED,
        ALREADY_DELETED,
        NOT_AUTHORIZED,
        NOT_FOUND
    }

    public record SoftDeleteResult(Comment comment, SoftDeleteStatus status) {
    }
}
