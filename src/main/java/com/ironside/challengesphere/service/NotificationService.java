package com.ironside.challengesphere.service;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.Submission;

import java.time.LocalDate;
import java.util.Collection;

public interface NotificationService {

    Notification createNotification(Long userId, String type, String message, String actionObject);

    Notification notifyChallengeUpdate(Long recipientUserId, String message, String actionObject);

    Notification notifyFollow(Long recipientUserId, Long actorUserId);

    void notifyFollowAsync(Long recipientUserId, Long actorUserId);

    int notifySubmission(Submission submission, Challenge challenge, Collection<Long> recipientUserIds);

    void notifyChallengeInvitesAsync(Challenge challenge, Long actorUserId, Collection<Long> requestedRecipientUserIds);

    int notifyChallengeJoined(Challenge challenge, Long joiningUserId, Collection<Long> recipientUserIds);

    void notifyChallengeJoinedAsync(Challenge challenge, Long joiningUserId, Collection<Long> recipientUserIds);

    Notification notifyGlory(Submission submission, Long actorUserId);

    void notifyGloryAsync(Submission submission, Long actorUserId);

    Notification notifyComment(Submission submission, Long actorUserId, Long commentId);

    void notifyCommentAsync(Submission submission, Long actorUserId, Long commentId);

    void notifyMomentumMilestonesAfterSubmissionAsync(
            Long userId,
            Long challengeId,
            LocalDate currentDate,
            boolean evaluateRank);
}
