package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.CommentResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Comment;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.CommentRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.CommentService;
import com.ironside.challengesphere.service.NotificationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger logger = LogManager.getLogger(CommentServiceImpl.class);
    private static final int MAX_LIMIT = 100;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private DynamoDbCursorCodec cursorCodec;

    private CommentResponse toCommentResponse(Comment comment, User user) {
        CommentResponse response = CommentResponse.from(comment, user);
        if (response.getProfilePicUrl() != null && !response.getProfilePicUrl().isBlank()) {
            response.setProfilePicUrl(
                    mediaUrlService.toReadableProfilePhoto(user.getId(), response.getProfilePicUrl()));
        }
        return response;
    }

    @Override
    public CursorPageResponse<CommentResponse> getCommentsForSubmission(
            String submissionId,
            String callerId,
            int limit,
            String cursor
    ) throws ChallengeSphereException {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        String queryBinding = "comments:v1|submission=" + submissionId
                + "|viewer=" + callerId
                + "|limit=" + boundedLimit;
        logger.info("Getting comments for submission: {}, limit: {}", submissionId, boundedLimit);

        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (!canInteractWithSubmission(submission, callerId)) {
            throw new ChallengeSphereException(
                    "Not authorized to view comments on this submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        Map<String, AttributeValue> continuation = decodeCursor(cursor, queryBinding);
        Set<String> blockedUserIds = Set.copyOf(
                userBlockRepository.findActiveBlockedCounterpartIds(callerId)
        );
        List<Comment> visibleComments = new ArrayList<>(boundedLimit);
        for (int pageNumber = 0; pageNumber < 10 && visibleComments.size() < boundedLimit; pageNumber++) {
            DynamoPage<Comment> page = commentRepository.findActiveCommentsPageBySubmissionId(
                    submissionId,
                    Math.max(1, boundedLimit - visibleComments.size()),
                    continuation
            );
            page.items().stream()
                    .filter(comment -> !blockedUserIds.contains(comment.getUserId()))
                    .forEach(visibleComments::add);
            continuation = page.lastEvaluatedKey();
            if (continuation.isEmpty()) break;
        }

        // Batch-fetch all authors for this page — avoids N+1
        Set<String> userIds = visibleComments.stream()
                .map(Comment::getUserId)
                .collect(Collectors.toSet());
        Map<String, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<CommentResponse> enriched = visibleComments.stream()
                .filter(comment -> userMap.containsKey(comment.getUserId()))
                .map(c -> toCommentResponse(c, userMap.get(c.getUserId())))
                .collect(Collectors.toList());

        boolean hasMore = !continuation.isEmpty();
        return CursorPageResponse.of(
                enriched,
                encodeCursor(queryBinding, continuation),
                hasMore
        );
    }

    private Map<String, AttributeValue> decodeCursor(String cursor, String queryBinding)
            throws ChallengeSphereException {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }
        try {
            return cursorCodec.decode(cursor, queryBinding).lastEvaluatedKey();
        } catch (IllegalArgumentException exception) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private String encodeCursor(String queryBinding, Map<String, AttributeValue> continuation) {
        if (continuation == null || continuation.isEmpty()) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(continuation)
        );
    }

    @Override
    @Transactional
    public CommentResponse createComment(String submissionId, String userId, String content) throws ChallengeSphereException {
        logger.info("Creating comment for submission: {} by user: {}", submissionId, userId);

        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (!canInteractWithSubmission(submission, userId)) {
            throw new ChallengeSphereException(
                    "Not authorized to comment on this submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        if (content == null || content.trim().isEmpty()) {
            throw new ChallengeSphereException(
                    "Comment content cannot be empty",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        TextContentSafetyPolicy.requireAllowed(content, "comment");

        Comment comment = new Comment();
        comment.setSubmissionId(submissionId);
        comment.setUserId(userId);
        comment.setContent(content.trim());
        comment.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        comment.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        comment.setActive(true);

        Comment savedComment = commentRepository.createWithSubmissionCommentCount(comment);
        logger.info("Created comment with id: {} for submission: {}", savedComment.getId(), submissionId);

        notifyCommentAfterCommit(submission, userId, savedComment.getId());

        User author = userRepository.findById(userId).orElse(null);
        return toCommentResponse(savedComment, author);
    }

    @Override
    @Transactional
    public Comment updateComment(String commentId, String userId, String content) throws ChallengeSphereException {
        logger.info("Updating comment: {} by user: {}", commentId, userId);

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Comment not found with id: " + commentId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (!comment.getActive()) {
            throw new ChallengeSphereException("Cannot update a deleted comment", HttpStatus.BAD_REQUEST, logger);
        }
        if (!comment.getUserId().equals(userId)) {
            throw new ChallengeSphereException("User not authorized to update this comment", HttpStatus.FORBIDDEN, logger);
        }
        if (content == null || content.trim().isEmpty()) {
            throw new ChallengeSphereException("Comment content cannot be empty", HttpStatus.BAD_REQUEST, logger);
        }
        TextContentSafetyPolicy.requireAllowed(content, "comment");

        comment.setContent(content.trim());
        comment.setUpdatedAt(new Timestamp(System.currentTimeMillis()));

        Comment updatedComment = commentRepository.save(comment);
        logger.info("Updated comment with id: {}", commentId);

        return updatedComment;
    }

    @Override
    @Transactional
    public void deleteComment(String commentId, String userId) throws ChallengeSphereException {
        logger.info("Deleting comment: {} by user: {}", commentId, userId);

        CommentRepository.SoftDeleteResult result =
                commentRepository.softDeleteWithSubmissionCommentCount(commentId, userId);
        if (result.status() == CommentRepository.SoftDeleteStatus.NOT_FOUND) {
            throw new ChallengeSphereException(
                    "Comment not found with id: " + commentId,
                    HttpStatus.NOT_FOUND,
                    logger
            );
        }
        if (result.status() == CommentRepository.SoftDeleteStatus.NOT_AUTHORIZED) {
            throw new ChallengeSphereException(
                    "User not authorized to delete this comment",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (result.status() == CommentRepository.SoftDeleteStatus.ALREADY_DELETED) {
            throw new ChallengeSphereException("Comment already deleted", HttpStatus.BAD_REQUEST, logger);
        }

        logger.info("Soft deleted comment with id: {}", commentId);
    }

    private boolean canInteractWithSubmission(Submission submission, String viewerId) {
        if (submission == null || viewerId == null) {
            return false;
        }
        if (viewerId.equals(submission.getUserId())) {
            return true;
        }
        if (userBlockRepository.existsActiveBlockBetween(viewerId, submission.getUserId())) {
            return false;
        }
        if (!Boolean.TRUE.equals(submission.getActive())) {
            return false;
        }

        Challenge challenge = challengeRepository.findById(submission.getChallengeId()).orElse(null);
        if (challenge == null || !Boolean.TRUE.equals(challenge.getActive())) {
            return false;
        }

        if (!Boolean.TRUE.equals(challenge.getIsPrivate()) && !Boolean.TRUE.equals(submission.getIsPrivate())) {
            return true;
        }

        return userChallengeRepository.findByUserIdAndChallengeId(viewerId, submission.getChallengeId())
                .map(userChallenge -> Boolean.TRUE.equals(userChallenge.getActive())
                        && !Boolean.TRUE.equals(userChallenge.getBanned()))
                .orElse(false);
    }

    private void notifyCommentAfterCommit(Submission submission, String userId, String commentId) {
        Runnable trigger = () -> {
            try {
                notificationService.notifyCommentAsync(submission, userId, commentId);
            } catch (Exception e) {
                logger.warn("Failed to schedule comment notification for commentId={}: {}", commentId, e.getClass().getSimpleName());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trigger.run();
                }
            });
            return;
        }

        trigger.run();
    }
}
