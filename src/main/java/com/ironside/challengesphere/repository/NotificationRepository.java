package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Repository
public class NotificationRepository extends AbstractDynamoRepository<Notification> {

    private static final Pattern ACTOR_USER_ID = Pattern.compile(
            "\\\"actorUserId\\\"\\s*:\\s*(?:\\\"([^\\\"]+)\\\"|([A-Za-z0-9_-]+))"
    );

    public NotificationRepository(DynamoDomainStore store) {
        super(store, "NOTIFICATION", Notification.class);
    }

    @Override
    public Notification save(Notification notification) {
        if (notification.getSentAt() == null) notification.setSentAt(Timestamp.from(Instant.now()));
        return super.save(notification);
    }

    public List<Notification> findByUserIdOrderBySentAtDesc(String userId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"), "NOTIFICATION#", false);
    }

    public DynamoPage<Notification> findPageByUserIdOrderBySentAtDesc(
            String userId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return queryProjectionPage(
                "USER#" + requireText(userId, "userId"),
                "NOTIFICATION#",
                limit,
                exclusiveStartKey,
                false
        );
    }

    public List<Notification> findByType(String type) {
        return queryProjectionPartition("NOTIFICATION#TYPE#" + normalize(type), "NOTIFICATION#", false);
    }

    public List<Notification> findByUserIdAndTypeOrderBySentAtDesc(String userId, String type) {
        return findByUserIdOrderBySentAtDesc(userId).stream()
                .filter(notification -> normalize(type).equals(normalize(notification.getType())))
                .toList();
    }

    public List<Notification> findByActionObject(String actionObject) {
        return queryProjectionPartition(actionPartition(actionObject), "NOTIFICATION#", false).stream()
                .filter(notification -> java.util.Objects.equals(actionObject, notification.getActionObject()))
                .toList();
    }

    public List<Notification> findByActorUserId(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            return List.of();
        }
        return queryProjectionPartition(
                "NOTIFICATION#ACTOR#" + actorUserId,
                "NOTIFICATION#",
                false
        );
    }

    public List<Notification> findByUserIdAndActionObject(String userId, String actionObject) {
        return findByUserIdOrderBySentAtDesc(userId).stream()
                .filter(notification -> java.util.Objects.equals(actionObject, notification.getActionObject()))
                .toList();
    }

    public List<Notification> findRecentNotifications(Integer limit) {
        int bounded = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        return store.query("NOTIFICATION#RECENT", "NOTIFICATION#", bounded, java.util.Map.of(), false,
                Notification.class, true).items();
    }

    public List<Notification> findRecentNotificationsByUserId(String userId, Integer limit) {
        int bounded = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        return store.query("USER#" + requireText(userId, "userId"), "NOTIFICATION#", bounded,
                java.util.Map.of(), false, Notification.class, true).items();
    }

    public Long countNotificationsByUserId(String userId) {
        return (long) findByUserIdOrderBySentAtDesc(userId).size();
    }

    public Long countUnreadNotificationsByUserId(String userId) {
        return findByUserIdOrderBySentAtDesc(userId).stream().filter(notification -> notification.getReadAt() == null).count();
    }

    public Long countNotificationsByUserIdAndType(String userId, String type) {
        return (long) findByUserIdAndTypeOrderBySentAtDesc(userId, type).size();
    }

    public int markAllAsReadByUserId(String userId) {
        Timestamp now = Timestamp.from(Instant.now());
        int changed = 0;
        for (Notification notification : findByUserIdOrderBySentAtDesc(userId)) {
            if (notification.getReadAt() == null) {
                notification.setReadAt(now);
                save(notification);
                changed++;
            }
        }
        return changed;
    }

    public int markAsRead(String userId, String notificationId) {
        Notification notification = findById(notificationId).orElse(null);
        if (notification == null || !userId.equals(notification.getUserId()) || notification.getReadAt() != null) return 0;
        notification.setReadAt(Timestamp.from(Instant.now()));
        save(notification);
        return 1;
    }

    public void deleteByUserId(String userId) {
        findByUserIdOrderBySentAtDesc(userId).forEach(this::delete);
    }

    public int deleteActivityByUserId(String userId) {
        int deleted = 0;
        for (Notification notification : findByUserIdOrderBySentAtDesc(userId)) {
            String type = normalize(notification.getType());
            if (!type.equals("CHALLENGE_INVITE") && !type.equals("INVITE") && !type.equals("FOLLOW")) {
                delete(notification);
                deleted++;
            }
        }
        return deleted;
    }

    public void deleteByUserIdAndType(String userId, String type) {
        findByUserIdAndTypeOrderBySentAtDesc(userId, type).forEach(this::delete);
    }

    public int deleteChallengeInvitesByChallengeId(String challengeId) {
        List<Notification> invites = challengeInvites(challengeId);
        invites.forEach(this::delete);
        return invites.size();
    }

    public int deleteChallengeInviteByChallengeIdAndUserId(String challengeId, String userId) {
        List<Notification> matches = challengeInvites(challengeId).stream()
                .filter(notification -> userId.equals(notification.getUserId())).toList();
        matches.forEach(this::delete);
        return matches.size();
    }

    public int deleteInteractionsBetweenUsers(String firstUserId, String secondUserId) {
        int deleted = deleteInteractions(firstUserId, secondUserId);
        return deleted + deleteInteractions(secondUserId, firstUserId);
    }

    private int deleteInteractions(String inboxUserId, String actorUserId) {
        int deleted = 0;
        for (Notification notification : findByUserIdOrderBySentAtDesc(inboxUserId)) {
            String action = notification.getActionObject();
            if (action != null && action.contains(actorUserId)) {
                delete(notification);
                deleted++;
            }
        }
        return deleted;
    }

    private List<Notification> challengeInvites(String challengeId) {
        return queryProjectionPartition("CHALLENGE#" + requireText(challengeId, "challengeId"), "NOTIFICATION#INVITE#", false);
    }

    @Override
    protected String id(Notification entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(Notification entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(Notification entity) {
        String sent = entity.getSentAt() == null ? entity.getId() : entity.getSentAt().toInstant().toString();
        String suffix = sent + '#' + entity.getId();
        List<DynamoProjection> result = new ArrayList<>();
        if (entity.getUserId() != null) result.add(DynamoProjection.of("USER#" + entity.getUserId(), "NOTIFICATION#" + suffix));
        result.add(DynamoProjection.of("NOTIFICATION#TYPE#" + normalize(entity.getType()), "NOTIFICATION#" + suffix));
        result.add(DynamoProjection.of("NOTIFICATION#RECENT", "NOTIFICATION#" + suffix));
        if (entity.getActionObject() != null) result.add(DynamoProjection.of(actionPartition(entity.getActionObject()), "NOTIFICATION#" + suffix));
        String actorUserId = actorUserId(entity.getActionObject());
        if (actorUserId != null) {
            result.add(DynamoProjection.of(
                    "NOTIFICATION#ACTOR#" + actorUserId,
                    "NOTIFICATION#" + suffix
            ));
        }
        if (entity.getChallengeId() != null && normalize(entity.getType()).contains("INVITE")) {
            result.add(DynamoProjection.of("CHALLENGE#" + entity.getChallengeId(), "NOTIFICATION#INVITE#" + suffix));
        }
        return result;
    }

    private static String actionPartition(String actionObject) {
        return "NOTIFICATION#ACTION#" + Integer.toUnsignedString(requireText(actionObject, "actionObject").hashCode(), 16);
    }

    private static String actorUserId(String actionObject) {
        if (actionObject == null || actionObject.isBlank()) {
            return null;
        }
        Matcher matcher = ACTOR_USER_ID.matcher(actionObject);
        if (!matcher.find()) {
            return null;
        }
        String quoted = matcher.group(1);
        return quoted == null ? matcher.group(2) : quoted;
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank() ? "UNKNOWN" : value.strip()).toUpperCase(Locale.ROOT);
    }
}
