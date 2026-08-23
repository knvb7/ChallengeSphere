package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.LeaderboardEntry;
import com.ironside.challengesphere.model.LeaderboardEntry.LeaderboardStatus;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.User;
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

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** DynamoDB-backed leaderboard materialization. */
@Repository
public class LeaderboardEntryRepository extends AbstractDynamoRepository<LeaderboardEntry> {

    private static final String ENTITY_TYPE = "LEADERBOARD_ENTRY";
    private static final int MAX_WRITE_ATTEMPTS = 8;
    private static final int MAX_FILTERED_CANDIDATES = 500;

    private static final Comparator<LeaderboardEntry> SCORE_DESC = Comparator
            .comparingDouble((LeaderboardEntry entry) -> score(entry.getScore()))
            .thenComparing(LeaderboardEntry::getId, Comparator.nullsLast(String::compareTo))
            .reversed();

    private final UserBlockRepository userBlockRepository;
    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final TransactionalDynamoWriter writer;

    public LeaderboardEntryRepository(
            DynamoDomainStore store,
            UserBlockRepository userBlockRepository,
            ChallengeRepository challengeRepository,
            UserRepository userRepository,
            TransactionalDynamoWriter writer
    ) {
        super(store, ENTITY_TYPE, LeaderboardEntry.class);
        this.userBlockRepository = userBlockRepository;
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.writer = writer;
    }

    @Override
    public LeaderboardEntry save(LeaderboardEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Leaderboard entry must not be null");
        }
        if (entry.getId() == null || entry.getId().isBlank()) {
            entry.setId(store.nextId());
        }
        Optional<TransactionalDynamoWriter.Versioned<LeaderboardEntry>> previous =
                writer.loadVersioned(ENTITY_TYPE, entry.getId(), LeaderboardEntry.class);
        prepareForSave(entry);
        writer.save(
                ENTITY_TYPE,
                entry.getId(),
                entry,
                allProjections(entry),
                previous.map(value -> allProjections(value.value())).orElseGet(List::of),
                uniqueProjections(entry),
                previous.map(value -> uniqueProjections(value.value())).orElseGet(List::of),
                false,
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return entry;
    }

    @Override
    public void delete(LeaderboardEntry entry) {
        if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
            return;
        }
        writer.loadVersioned(ENTITY_TYPE, entry.getId(), LeaderboardEntry.class)
                .ifPresent(current -> writer.delete(
                        ENTITY_TYPE,
                        entry.getId(),
                        allProjections(current.value()),
                        uniqueProjections(current.value()),
                        current.version()
                ));
    }

    private void prepareForSave(LeaderboardEntry entry) {
        LocalDateTime now = LocalDateTime.now();
        if (entry.getCreatedAt() == null) entry.setCreatedAt(now);
        if (entry.getFirstSubmissionAt() == null) entry.setFirstSubmissionAt(now);
        entry.setUpdatedAt(now);
        if (entry.getStatus() == null) entry.setStatus(LeaderboardStatus.ACTIVE);
        if (entry.getScore() == null) entry.setScore(0.0);
        if (entry.getSubmissionCount() == null) entry.setSubmissionCount(0);
        if (entry.getGloryPoints() == null) entry.setGloryPoints(0);
        if (entry.getStreakDays() == null) entry.setStreakDays(0);
        if (entry.getBestScore() == null) entry.setBestScore(entry.getScore());
    }

    /** Atomically creates the challenge/user entry when it does not yet exist. */
    public LeaderboardEntry ensureEntry(String challengeId, String userId) {
        return mutatePair(challengeId, userId, true, ignored -> false)
                .entry()
                .orElseThrow();
    }

    /**
     * Applies event deltas to the latest canonical payload and retries on an
     * optimistic version conflict. Score and counters never fall below zero.
     */
    public LeaderboardEntry applyDeltas(
            String challengeId,
            String userId,
            double scoreDelta,
            int submissionDelta,
            int gloryDelta
    ) {
        return mutatePair(
                challengeId,
                userId,
                true,
                entry -> applyDeltas(entry, scoreDelta, submissionDelta, gloryDelta)
        ).entry().orElseThrow();
    }

    /**
     * Builds the version-checked leaderboard side of an enclosing glory
     * transaction. The fixed challenge/user uniqueness item serializes a
     * concurrent first write; subsequent writes are serialized by the
     * canonical aggregate version.
     */
    TransactionalDynamoWriter.AggregateWrite gloryCountAggregateWrite(
            String challengeId,
            String userId,
            int delta
    ) {
        requireText(challengeId, "challengeId");
        requireText(userId, "userId");
        if (delta != 1 && delta != -1) {
            throw new IllegalArgumentException("glory count delta must be 1 or -1");
        }

        Optional<LeaderboardEntry> indexed = findByChallengeIdAndUserId(challengeId, userId);
        if (indexed.isEmpty()) {
            LeaderboardEntry created = newEntry(challengeId, userId);
            created.setId(store.nextId());
            prepareForSave(created);
            applyDeltas(created, 0.0, 0, delta);
            prepareForSave(created);
            return new TransactionalDynamoWriter.AggregateWrite(
                    ENTITY_TYPE,
                    created.getId(),
                    created,
                    allProjections(created),
                    List.of(),
                    uniqueProjections(created),
                    List.of(),
                    true,
                    null
            );
        }

        String entryId = indexed.get().getId();
        TransactionalDynamoWriter.Versioned<LeaderboardEntry> versioned = writer
                .loadVersioned(ENTITY_TYPE, entryId, LeaderboardEntry.class)
                .orElseThrow(() -> new DataIntegrityViolationException(
                        "Leaderboard projection has no canonical entry for challenge="
                                + challengeId + ", user=" + userId
                ));
        LeaderboardEntry current = versioned.value();
        List<DynamoProjection> previousProjections = allProjections(current);
        List<DynamoProjection> previousUnique = uniqueProjections(current);
        applyDeltas(current, 0.0, 0, delta);
        prepareForSave(current);
        return new TransactionalDynamoWriter.AggregateWrite(
                ENTITY_TYPE,
                current.getId(),
                current,
                allProjections(current),
                previousProjections,
                uniqueProjections(current),
                previousUnique,
                false,
                versioned.version()
        );
    }

    /** Applies deltas only when an entry already exists. */
    public Optional<LeaderboardEntry> applyExistingDeltas(
            String challengeId,
            String userId,
            double scoreDelta,
            int submissionDelta,
            int gloryDelta
    ) {
        return mutatePair(
                challengeId,
                userId,
                false,
                entry -> applyDeltas(entry, scoreDelta, submissionDelta, gloryDelta)
        ).entry();
    }

    /** Sets an absolute score without racing concurrent delta writers. */
    public LeaderboardEntry setAbsoluteScore(
            String challengeId,
            String userId,
            double score
    ) {
        return mutatePair(challengeId, userId, true, entry -> {
            entry.setScore(score);
            return true;
        }).entry().orElseThrow();
    }

    /** Replaces source-derived metrics during an explicit leaderboard rebuild. */
    public LeaderboardEntry replaceComputedMetrics(
            String challengeId,
            String userId,
            double score,
            int submissionCount,
            int gloryPoints,
            int streakDays
    ) {
        return mutatePair(challengeId, userId, true, entry -> {
            entry.setScore(score);
            entry.setSubmissionCount(Math.max(0, submissionCount));
            entry.setGloryPoints(Math.max(0, gloryPoints));
            entry.setStreakDays(Math.max(0, streakDays));
            return true;
        }).entry().orElseThrow();
    }

    public Optional<LeaderboardEntry> findByChallengeIdAndUserId(String challengeId, String userId) {
        return queryProjectionPage(
                exactPartition(challengeId, userId),
                "ITEM#",
                1,
                Map.of(),
                true
        ).items().stream().findFirst();
    }

    public List<LeaderboardEntry> findByChallengeIdAndUserIds(String challengeId, List<String> userIds) {
        return userIds.stream()
                .distinct()
                .map(userId -> findByChallengeIdAndUserId(challengeId, userId))
                .flatMap(Optional::stream)
                .toList();
    }

    public boolean existsByChallengeIdAndUserId(String challengeId, String userId) {
        return findByChallengeIdAndUserId(challengeId, userId).isPresent();
    }

    public List<LeaderboardEntry> findByChallengeIdAndStatusOrderByScoreDesc(
            String challengeId, LeaderboardStatus status
    ) {
        return queryChallenge(challengeId, status);
    }

    public List<LeaderboardEntry> findActiveByChallengeId(String challengeId) {
        return queryChallenge(challengeId, LeaderboardStatus.ACTIVE);
    }

    public List<LeaderboardEntry> findTopByChallengeId(String challengeId, Pageable pageable) {
        return slice(findActiveByChallengeId(challengeId), pageable);
    }

    public List<LeaderboardEntry> findTopWithUserByChallengeId(String challengeId, Pageable pageable) {
        return findTopByChallengeId(challengeId, pageable);
    }

    public List<LeaderboardEntry> findTopByChallengeIdForViewer(
            String challengeId, String viewerUserId, Pageable pageable
    ) {
        return slice(visibleEntries(challengeId, viewerUserId), pageable);
    }

    public DynamoPage<LeaderboardEntry> findActivePageForViewer(
            String challengeId,
            String viewerUserId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        Set<String> hiddenUserIds = new LinkedHashSet<>();
        List<LeaderboardEntry> visible = new ArrayList<>(boundedLimit);
        int inspectedCandidates = 0;
        Map<String, AttributeValue> continuation = exclusiveStartKey == null
                ? Map.of()
                : Map.copyOf(exclusiveStartKey);
        do {
            int queryLimit = Math.min(
                    Math.max(1, boundedLimit - visible.size()),
                    MAX_FILTERED_CANDIDATES - inspectedCandidates
            );
            DynamoPage<LeaderboardEntry> page = queryActivePage(
                    challengeId,
                    queryLimit,
                    continuation
            );
            inspectedCandidates += page.items().size();
            if (viewerUserId != null && !page.items().isEmpty()) {
                hiddenUserIds.addAll(userBlockRepository.findActiveBlockedCounterpartIds(
                        viewerUserId,
                        page.items().stream().map(LeaderboardEntryRepository::userId).toList()
                ));
            }
            page.items().stream()
                    .filter(entry -> !hiddenUserIds.contains(userId(entry)))
                    .forEach(visible::add);
            continuation = page.lastEvaluatedKey();
        } while (visible.size() < boundedLimit
                && inspectedCandidates < MAX_FILTERED_CANDIDATES
                && !continuation.isEmpty());
        return new DynamoPage<>(visible, continuation);
    }

    public ViewerRankStats getViewerRankStats(
            String challengeId,
            String userId,
            String viewerUserId
    ) {
        Set<String> hiddenUserIds = new LinkedHashSet<>();
        Map<String, AttributeValue> continuation = Map.of();
        long totalParticipants = 0L;
        Long rank = null;
        int inspectedCandidates = 0;
        do {
            DynamoPage<LeaderboardEntry> page = queryActivePage(
                    challengeId,
                    Math.min(100, MAX_FILTERED_CANDIDATES - inspectedCandidates),
                    continuation
            );
            inspectedCandidates += page.items().size();
            if (viewerUserId != null && !page.items().isEmpty()) {
                hiddenUserIds.addAll(userBlockRepository.findActiveBlockedCounterpartIds(
                        viewerUserId,
                        page.items().stream().map(LeaderboardEntryRepository::userId).toList()
                ));
            }
            for (LeaderboardEntry entry : page.items()) {
                if (hiddenUserIds.contains(userId(entry))) {
                    continue;
                }
                totalParticipants++;
                if (userId.equals(userId(entry))) {
                    rank = totalParticipants;
                    return new ViewerRankStats(rank, totalParticipants, false);
                }
            }
            continuation = page.lastEvaluatedKey();
        } while (inspectedCandidates < MAX_FILTERED_CANDIDATES && !continuation.isEmpty());
        return new ViewerRankStats(rank, totalParticipants, !continuation.isEmpty());
    }

    public Integer getUserRank(String challengeId, String userId) {
        LeaderboardEntry target = findByChallengeIdAndUserId(challengeId, userId).orElse(null);
        if (target == null || target.getStatus() != LeaderboardStatus.ACTIVE) return null;
        Map<String, AttributeValue> continuation = Map.of();
        int rank = 0;
        do {
            DynamoPage<LeaderboardEntry> page = queryActivePage(challengeId, 100, continuation);
            for (LeaderboardEntry entry : page.items()) {
                rank++;
                if (userId.equals(userId(entry))) {
                    return rank;
                }
            }
            continuation = page.lastEvaluatedKey();
        } while (!continuation.isEmpty());
        return null;
    }

    public Long getUserRankForViewer(String challengeId, String userId, String viewerUserId) {
        LeaderboardEntry target = findByChallengeIdAndUserId(challengeId, userId).orElse(null);
        if (target == null || target.getStatus() != LeaderboardStatus.ACTIVE) return null;
        return getViewerRankStats(challengeId, userId, viewerUserId).rank();
    }

    public List<LeaderboardEntry> findEntriesAroundUser(
            String challengeId, String userId, int above, int below
    ) {
        int aboveLimit = Math.max(0, above);
        int belowLimit = Math.max(0, below);
        ArrayDeque<LeaderboardEntry> preceding = new ArrayDeque<>();
        List<LeaderboardEntry> window = new ArrayList<>();
        Map<String, AttributeValue> continuation = Map.of();
        boolean found = false;
        int remainingBelow = belowLimit;
        do {
            DynamoPage<LeaderboardEntry> page = queryActivePage(challengeId, 100, continuation);
            for (LeaderboardEntry entry : page.items()) {
                if (!found) {
                    if (userId.equals(userId(entry))) {
                        window.addAll(preceding);
                        window.add(entry);
                        found = true;
                        if (remainingBelow == 0) {
                            return List.copyOf(window);
                        }
                    } else if (aboveLimit > 0) {
                        if (preceding.size() == aboveLimit) {
                            preceding.removeFirst();
                        }
                        preceding.addLast(entry);
                    }
                    continue;
                }
                window.add(entry);
                if (--remainingBelow == 0) {
                    return List.copyOf(window);
                }
            }
            continuation = page.lastEvaluatedKey();
        } while (!continuation.isEmpty());
        return found ? List.copyOf(window) : List.of();
    }

    public Page<LeaderboardEntry> findByChallengeIdPaginated(String challengeId, Pageable pageable) {
        return page(findActiveByChallengeId(challengeId), pageable);
    }

    public Long countParticipants(String challengeId) {
        return (long) findActiveByChallengeId(challengeId).size();
    }

    public Long countParticipantsForViewer(String challengeId, String viewerUserId) {
        return getViewerRankStats(challengeId, "", viewerUserId).totalParticipants();
    }

    public Double getAverageScore(String challengeId) {
        return findActiveByChallengeId(challengeId).stream().mapToDouble(entry -> score(entry.getScore()))
                .average().orElse(0.0);
    }

    public Double getMaxScore(String challengeId) {
        return findActiveByChallengeId(challengeId).stream().mapToDouble(entry -> score(entry.getScore()))
                .max().orElse(0.0);
    }

    public Long getTotalSubmissions(String challengeId) {
        return findActiveByChallengeId(challengeId).stream()
                .mapToLong(entry -> entry.getSubmissionCount() == null ? 0 : entry.getSubmissionCount()).sum();
    }

    public List<LeaderboardEntry> findByUserIdAndStatusOrderByScoreDesc(
            String userId, LeaderboardStatus status
    ) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"),
                "LEADERBOARD#STATUS#" + statusName(status) + "#SCORE#", true);
    }

    public List<LeaderboardEntry> findActiveByUserId(String userId) {
        return findByUserIdAndStatusOrderByScoreDesc(userId, LeaderboardStatus.ACTIVE);
    }

    public List<LeaderboardEntry> findByUserId(String userId) {
        return queryProjectionPartition(
                "USER#" + requireText(userId, "userId"),
                "LEADERBOARD#STATUS#",
                true
        );
    }

    public List<LeaderboardEntry> findUserBestRankings(String userId, Pageable pageable) {
        List<LeaderboardEntry> entries = new ArrayList<>(findActiveByUserId(userId));
        entries.sort(Comparator.comparing(LeaderboardEntry::getCurrentRank,
                Comparator.nullsLast(Integer::compareTo)));
        return slice(entries, pageable);
    }

    public void updateRanksForChallenge(String challengeId) {
        List<LeaderboardEntry> entries = findActiveByChallengeId(challengeId);
        for (int index = 0; index < entries.size(); index++) {
            LeaderboardEntry entry = entries.get(index);
            int rank = index + 1;
            mutatePair(challengeId, userId(entry), false, latest -> {
                if (latest.getStatus() != LeaderboardStatus.ACTIVE) {
                    return false;
                }
                latest.setCurrentRank(rank);
                return true;
            });
        }
    }

    public void deleteByChallengeId(String challengeId) {
        queryChallenge(challengeId, null).forEach(this::delete);
    }

    public void disqualifyUser(String challengeId, String userId) {
        updateStatus(challengeId, userId, LeaderboardStatus.DISQUALIFIED, null);
    }

    public int markUserLeft(String challengeId, String userId) {
        return updateStatus(challengeId, userId, LeaderboardStatus.LEFT,
                Set.of(LeaderboardStatus.ACTIVE, LeaderboardStatus.COMPLETED));
    }

    public int reactivateUser(String challengeId, String userId) {
        return updateStatus(challengeId, userId, LeaderboardStatus.ACTIVE, Set.of(LeaderboardStatus.LEFT));
    }

    public void markChallengeCompleted(String challengeId) {
        for (LeaderboardEntry entry : findActiveByChallengeId(challengeId)) {
            mutatePair(challengeId, userId(entry), false, latest -> {
                if (latest.getStatus() != LeaderboardStatus.ACTIVE) {
                    return false;
                }
                latest.setStatus(LeaderboardStatus.COMPLETED);
                return true;
            });
        }
    }

    public List<LeaderboardEntry> findEntriesForRedisSync(String challengeId) {
        return findActiveByChallengeId(challengeId);
    }

    private int updateStatus(
            String challengeId, String userId, LeaderboardStatus target, Set<LeaderboardStatus> allowed
    ) {
        MutationResult result = mutatePair(challengeId, userId, false, entry -> {
            if (allowed != null && !allowed.contains(entry.getStatus())) {
                return false;
            }
            entry.setStatus(target);
            return true;
        });
        return result.changed() ? 1 : 0;
    }

    private boolean applyDeltas(
            LeaderboardEntry entry,
            double scoreDelta,
            int submissionDelta,
            int gloryDelta
    ) {
        double currentScore = score(entry.getScore());
        entry.setScore(Math.max(0.0, currentScore + scoreDelta));
        entry.setSubmissionCount(Math.max(
                0,
                number(entry.getSubmissionCount()) + submissionDelta
        ));
        entry.setGloryPoints(Math.max(
                0,
                number(entry.getGloryPoints()) + gloryDelta
        ));
        if (submissionDelta != 0) {
            entry.setLastSubmissionAt(LocalDateTime.now());
        }
        return true;
    }

    private MutationResult mutatePair(
            String challengeId,
            String userId,
            boolean createIfMissing,
            Function<LeaderboardEntry, Boolean> mutation
    ) {
        requireText(challengeId, "challengeId");
        requireText(userId, "userId");
        DataIntegrityViolationException lastConflict = null;

        for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
            Optional<LeaderboardEntry> indexed = findByChallengeIdAndUserId(challengeId, userId);
            if (indexed.isEmpty()) {
                if (!createIfMissing) {
                    return MutationResult.missing();
                }
                LeaderboardEntry created = newEntry(challengeId, userId);
                created.setId(store.nextId());
                prepareForSave(created);
                mutation.apply(created);
                prepareForSave(created);
                try {
                    writer.save(
                            ENTITY_TYPE,
                            created.getId(),
                            created,
                            allProjections(created),
                            List.of(),
                            uniqueProjections(created),
                            List.of(),
                            true
                    );
                    return MutationResult.changed(created);
                } catch (DataIntegrityViolationException conflict) {
                    lastConflict = conflict;
                    continue;
                }
            }

            String entryId = indexed.get().getId();
            Optional<TransactionalDynamoWriter.Versioned<LeaderboardEntry>> versioned =
                    writer.loadVersioned(ENTITY_TYPE, entryId, LeaderboardEntry.class);
            if (versioned.isEmpty()) {
                continue;
            }
            LeaderboardEntry current = versioned.get().value();
            List<DynamoProjection> previousProjections = allProjections(current);
            List<DynamoProjection> previousUnique = uniqueProjections(current);
            boolean changed = Boolean.TRUE.equals(mutation.apply(current));
            if (!changed) {
                return MutationResult.unchanged(current);
            }
            prepareForSave(current);
            try {
                writer.save(
                        ENTITY_TYPE,
                        current.getId(),
                        current,
                        allProjections(current),
                        previousProjections,
                        uniqueProjections(current),
                        previousUnique,
                        false,
                        versioned.get().version()
                );
                return MutationResult.changed(current);
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }

        throw new DataIntegrityViolationException(
                "Leaderboard entry remained contended for challenge=" + challengeId
                        + ", user=" + userId,
                lastConflict
        );
    }

    private LeaderboardEntry newEntry(String challengeId, String userId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Challenge not found: " + challengeId
                ));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return new LeaderboardEntry(challenge, user);
    }

    private List<LeaderboardEntry> queryChallenge(String challengeId, LeaderboardStatus status) {
        String prefix = status == null ? "LEADERBOARD#STATUS#" :
                "LEADERBOARD#STATUS#" + statusName(status) + "#SCORE#";
        List<LeaderboardEntry> entries = new ArrayList<>(queryProjectionPartition(
                "CHALLENGE#" + requireText(challengeId, "challengeId"), prefix, true));
        entries.sort(SCORE_DESC);
        return List.copyOf(entries);
    }

    private DynamoPage<LeaderboardEntry> queryActivePage(
            String challengeId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return queryProjectionPage(
                "CHALLENGE#" + requireText(challengeId, "challengeId"),
                "LEADERBOARD#STATUS#ACTIVE#SCORE#",
                limit,
                exclusiveStartKey,
                true
        );
    }

    private List<LeaderboardEntry> visibleEntries(String challengeId, String viewerUserId) {
        return findActiveByChallengeId(challengeId).stream()
                .filter(entry -> viewerUserId == null
                        || !userBlockRepository.existsActiveBlockBetween(viewerUserId, userId(entry)))
                .toList();
    }

    private <T> List<T> slice(List<T> values, Pageable pageable) {
        int start = Math.toIntExact(Math.min(pageable.getOffset(), values.size()));
        int end = Math.min(start + pageable.getPageSize(), values.size());
        return List.copyOf(values.subList(start, end));
    }

    private Page<LeaderboardEntry> page(List<LeaderboardEntry> values, Pageable pageable) {
        int start = Math.toIntExact(Math.min(pageable.getOffset(), values.size()));
        int end = Math.min(start + pageable.getPageSize(), values.size());
        return new PageImpl<>(values.subList(start, end), pageable, values.size());
    }

    @Override protected String id(LeaderboardEntry entity) { return entity.getId(); }
    @Override protected void assignId(LeaderboardEntry entity, String id) { entity.setId(id); }

    @Override
    protected Collection<DynamoProjection> projections(LeaderboardEntry entry) {
        String challengeId = challengeId(entry);
        String userId = userId(entry);
        String status = statusName(entry.getStatus());
        String score = invertedScore(entry.getScore());
        String suffix = "LEADERBOARD#STATUS#" + status + "#SCORE#" + score + '#' + entry.getId();
        List<DynamoProjection> result = new ArrayList<>();
        result.add(DynamoProjection.of("CHALLENGE#" + challengeId, suffix));
        result.add(DynamoProjection.of("USER#" + userId, suffix));
        result.add(DynamoProjection.of(exactPartition(challengeId, userId), "ITEM#" + entry.getId()));
        return result;
    }

    private List<DynamoProjection> uniqueProjections(LeaderboardEntry entry) {
        return List.of(DynamoProjection.of(
                exactPartition(challengeId(entry), userId(entry)),
                "UNIQUE"
        ));
    }

    private static String exactPartition(String challengeId, String userId) {
        return "LEADERBOARD#CHALLENGE#" + requireText(challengeId, "challengeId")
                + "#USER#" + requireText(userId, "userId");
    }

    private static String challengeId(LeaderboardEntry entry) {
        if (entry.getChallenge() == null || entry.getChallenge().getId() == null) {
            throw new IllegalArgumentException("leaderboard challenge must be set");
        }
        return entry.getChallenge().getId();
    }

    private static String userId(LeaderboardEntry entry) {
        if (entry.getUser() == null || entry.getUser().getId() == null) {
            throw new IllegalArgumentException("leaderboard user must be set");
        }
        return entry.getUser().getId();
    }

    private static String statusName(LeaderboardStatus status) {
        return (status == null ? LeaderboardStatus.ACTIVE : status).name().toUpperCase(Locale.ROOT);
    }

    private static double score(Double value) { return value == null ? 0.0 : value; }

    private static int number(Integer value) { return value == null ? 0 : value; }

    private static String invertedScore(Double value) {
        double safe = Math.max(0.0, Math.min(score(value), 9_000_000_000_000.0));
        long normalized = Math.round(safe * 1_000.0);
        return String.format(Locale.ROOT, "%019d", Long.MAX_VALUE - normalized);
    }

    public record ViewerRankStats(Long rank, long totalParticipants, boolean truncated) {
    }

    private record MutationResult(Optional<LeaderboardEntry> entry, boolean changed) {
        private static MutationResult missing() {
            return new MutationResult(Optional.empty(), false);
        }

        private static MutationResult unchanged(LeaderboardEntry entry) {
            return new MutationResult(Optional.of(entry), false);
        }

        private static MutationResult changed(LeaderboardEntry entry) {
            return new MutationResult(Optional.of(entry), true);
        }
    }
}
