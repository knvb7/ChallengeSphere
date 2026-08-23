package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.response.ChallengeInviteDetailsResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.Submission;

import java.time.LocalDate;
import java.util.Collection;

public interface NotificationService {

    CursorPageResponse<Notification> getNotifications(String userId, int limit, String cursor)
            throws ChallengeSphereException;

    Notification createNotification(String userId, String type, String message, String actionObject);

    Notification notifyChallengeUpdate(String recipientUserId, String message, String actionObject);

    Notification notifyParticipantRemoved(
            Challenge challenge,
            String recipientUserId,
            String actorUserId,
            String reason);

    Notification notifyFollow(String recipientUserId, String actorUserId);

    void notifyFollowAsync(String recipientUserId, String actorUserId);

    int notifySubmission(Submission submission, Challenge challenge, Collection<String> recipientUserIds);

    void notifyChallengeInvitesAsync(Challenge challenge, String actorUserId, Collection<String> requestedRecipientUserIds);

    ChallengeInviteDetailsResponse getChallengeInviteDetails(String notificationId, String recipientUserId)
            throws ChallengeSphereException;

    int invalidateChallengeInvitesForChallenge(String challengeId);

    int invalidateChallengeInviteForRecipient(String challengeId, String recipientUserId);

    int notifyChallengeJoined(Challenge challenge, String joiningUserId, Collection<String> recipientUserIds);

    void notifyChallengeJoinedAsync(Challenge challenge, String joiningUserId, Collection<String> recipientUserIds);

    Notification notifyGlory(Submission submission, String actorUserId);

    void notifyGloryAsync(Submission submission, String actorUserId);

    Notification notifyComment(Submission submission, String actorUserId, String commentId);

    void notifyCommentAsync(Submission submission, String actorUserId, String commentId);

    void notifyMomentumMilestonesAfterSubmissionAsync(
            String userId,
            String challengeId,
            LocalDate currentDate,
            boolean evaluateRank);
}
