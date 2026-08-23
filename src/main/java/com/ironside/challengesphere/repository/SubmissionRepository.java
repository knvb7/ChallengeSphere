package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter;
import com.ironside.challengesphere.repository.projection.SubmissionPreviewProjection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** DynamoDB submission repository. Every read starts from a concrete partition. */
@Repository
public class SubmissionRepository extends AbstractDynamoRepository<Submission> {

    private static final int MAX_PUBLIC_FEED_CANDIDATES = 500;
    private static final int MAX_FILTERED_CANDIDATES = 500;
    private static final int PREVIEW_VISIBLE_CANDIDATES = 100;
    private static final int PUBLIC_FEED_PREFETCH_SIZE = 25;
    private static final String CURSOR_DONE = "__done";

    private static final Pattern IMAGE_MEDIA = Pattern.compile(
            "(?i)\\.(?:jpe?g|png|webp|heic|heif)(?:[?&#,\\\"'\\]\\)]|$)"
    );
    private static final Comparator<Submission> NEWEST = Comparator
            .comparing(SubmissionRepository::createdInstant)
            .thenComparing(Submission::getId, Comparator.nullsLast(String::compareTo))
            .reversed();
    private static final Comparator<Submission> OLDEST = NEWEST.reversed();
    private static final Comparator<Submission> MOST_GLORIES = Comparator
            .comparingLong((Submission submission) -> number(submission.getGloriesCount()))
            .thenComparingLong(submission -> number(submission.getCommentsCount()))
            .thenComparing(SubmissionRepository::createdInstant)
            .thenComparing(Submission::getId, Comparator.nullsLast(String::compareTo))
            .reversed();

    private final UserBlockRepository userBlockRepository;
    private final UserChallengeRepository userChallengeRepository;
    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final TransactionalDynamoWriter writer;

    public SubmissionRepository(
            DynamoDomainStore store,
            UserBlockRepository userBlockRepository,
            UserChallengeRepository userChallengeRepository,
            ChallengeRepository challengeRepository,
            UserRepository userRepository,
            FollowRepository followRepository,
            TransactionalDynamoWriter writer
    ) {
        super(store, "SUBMISSION", Submission.class);
        this.userBlockRepository = userBlockRepository;
        this.userChallengeRepository = userChallengeRepository;
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.followRepository = followRepository;
        this.writer = writer;
    }

    @Override
    public Submission save(Submission submission) {
        prepareForSave(submission);
        if (!hasText(submission.getId())) submission.setId(store.nextId());
        Optional<TransactionalDynamoWriter.Versioned<Submission>> previous =
                writer.loadVersioned(entityType(), submission.getId(), Submission.class);
        if (active(submission)
                && previous.map(TransactionalDynamoWriter.Versioned::value)
                .filter(previousSubmission -> !active(previousSubmission))
                .isPresent()) {
            submission.setFeedTimelineFanoutCursor(null);
            submission.setFeedTimelineFanoutCompletedAt(null);
        }
        if (active(submission) && submission.getDaySlot() == null) {
            int limit = challengeRepository.findById(submission.getChallengeId())
                    .filter(challenge -> Challenge.isOneOffMode(challenge.getChallengeMode()))
                    .map(ignored -> 1)
                    .orElse(5);
            return saveWithinDayLimit(submission, limit, previous);
        }
        saveTransactionally(submission, previous);
        return submission;
    }

    /**
     * Atomically writes a new submission and claims one fixed slot in its
     * user/challenge/day partition. Concurrent requests cannot exceed the cap.
     */
    public Submission saveWithinDayLimit(Submission submission, int maximumActiveSubmissions) {
        prepareForSave(submission);
        if (!hasText(submission.getId())) submission.setId(store.nextId());
        return saveWithinDayLimit(
                submission,
                maximumActiveSubmissions,
                writer.loadVersioned(entityType(), submission.getId(), Submission.class)
        );
    }

    @Override
    public void delete(Submission submission) {
        if (submission == null || !hasText(submission.getId())) return;
        writer.loadVersioned(entityType(), submission.getId(), Submission.class)
                .ifPresent(current -> writer.delete(
                        entityType(),
                        submission.getId(),
                        allProjections(current.value()),
                        daySlotProjection(current.value()),
                        current.version()
                ));
    }

    private void prepareForSave(Submission submission) {
        if (submission == null) throw new IllegalArgumentException("submission must not be null");
        Timestamp now = Timestamp.from(Instant.now());
        if (submission.getSubmissionTime() == null) submission.setSubmissionTime(now);
        if (submission.getCreatedAt() == null) submission.setCreatedAt(now);
        submission.setUpdatedAt(now);
        if (submission.getActive() == null) submission.setActive(true);
        if (submission.getIsPrivate() == null) submission.setIsPrivate(false);
        if (!hasText(submission.getModerationStatus())) submission.setModerationStatus(Submission.MODERATION_PENDING);
        if (submission.getGloriesCount() == null) submission.setGloriesCount(0L);
        if (submission.getCommentsCount() == null) submission.setCommentsCount(0L);
    }

    private Submission saveWithinDayLimit(
            Submission submission,
            int maximumActiveSubmissions,
            Optional<TransactionalDynamoWriter.Versioned<Submission>> previous
    ) {
        if (active(submission)
                && previous.map(TransactionalDynamoWriter.Versioned::value)
                .filter(previousSubmission -> !active(previousSubmission))
                .isPresent()) {
            submission.setFeedTimelineFanoutCursor(null);
            submission.setFeedTimelineFanoutCompletedAt(null);
        }
        if (maximumActiveSubmissions < 1 || maximumActiveSubmissions > 99) {
            throw new IllegalArgumentException("maximumActiveSubmissions must be between 1 and 99");
        }
        if (!active(submission)) {
            saveTransactionally(submission, previous);
            return submission;
        }
        DataIntegrityViolationException lastConflict = null;
        for (int slot = 1; slot <= maximumActiveSubmissions; slot++) {
            submission.setDaySlot(slot);
            try {
                saveTransactionally(submission, previous);
                return submission;
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        submission.setDaySlot(null);
        throw new SubmissionDayLimitExceededException(maximumActiveSubmissions, lastConflict);
    }

    private void saveTransactionally(
            Submission submission,
            Optional<TransactionalDynamoWriter.Versioned<Submission>> previous
    ) {
        writer.save(
                entityType(),
                submission.getId(),
                submission,
                allProjections(submission),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::allProjections).orElseGet(List::of),
                daySlotProjection(submission),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::daySlotProjection).orElseGet(List::of),
                previous.isEmpty(),
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
    }

    Optional<TransactionalDynamoWriter.Versioned<Submission>> loadVersionedForCommentMutation(
            String submissionId
    ) {
        return writer.loadVersioned(
                entityType(),
                requireText(submissionId, "submissionId"),
                Submission.class
        );
    }

    Optional<TransactionalDynamoWriter.Versioned<Submission>> loadVersionedForGloryMutation(
            String submissionId
    ) {
        return writer.loadVersioned(
                entityType(),
                requireText(submissionId, "submissionId"),
                Submission.class
        );
    }

    TransactionalDynamoWriter.AggregateWrite commentCountAggregateWrite(
            TransactionalDynamoWriter.Versioned<Submission> versioned,
            int delta
    ) {
        if (versioned == null) {
            throw new IllegalArgumentException("versioned submission must not be null");
        }
        if (delta != 1 && delta != -1) {
            throw new IllegalArgumentException("comment count delta must be 1 or -1");
        }

        Submission submission = versioned.value();
        List<DynamoProjection> previousProjections = allProjections(submission);
        List<DynamoProjection> previousDaySlot = daySlotProjection(submission);
        submission.setCommentsCount(Math.max(
                0L,
                number(submission.getCommentsCount()) + delta
        ));
        prepareForSave(submission);

        return new TransactionalDynamoWriter.AggregateWrite(
                entityType(),
                submission.getId(),
                submission,
                allProjections(submission),
                previousProjections,
                daySlotProjection(submission),
                previousDaySlot,
                false,
                versioned.version()
        );
    }

    TransactionalDynamoWriter.AggregateWrite gloryCountAggregateWrite(
            TransactionalDynamoWriter.Versioned<Submission> versioned,
            int delta
    ) {
        if (versioned == null) {
            throw new IllegalArgumentException("versioned submission must not be null");
        }
        if (delta != 1 && delta != -1) {
            throw new IllegalArgumentException("glory count delta must be 1 or -1");
        }

        Submission submission = versioned.value();
        List<DynamoProjection> previousProjections = allProjections(submission);
        List<DynamoProjection> previousDaySlot = daySlotProjection(submission);
        submission.setGloriesCount(Math.max(
                0L,
                number(submission.getGloriesCount()) + delta
        ));
        prepareForSave(submission);

        return new TransactionalDynamoWriter.AggregateWrite(
                entityType(),
                submission.getId(),
                submission,
                allProjections(submission),
                previousProjections,
                daySlotProjection(submission),
                previousDaySlot,
                false,
                versioned.version()
        );
    }

    private List<DynamoProjection> daySlotProjection(Submission submission) {
        if (!active(submission) || submission.getDaySlot() == null) return List.of();
        String partition = "SUBMISSION_DAY#USER#" + requireText(submission.getUserId(), "userId")
                + "#CHALLENGE#" + requireText(submission.getChallengeId(), "challengeId")
                + "#DAY#" + paddedDay(submission.getDayNumber());
        return List.of(DynamoProjection.of(
                partition,
                String.format(Locale.ROOT, "SLOT#%02d", submission.getDaySlot())
        ));
    }

    public List<String> findSubmissionIdsAwaitingModeration(Pageable pageable) {
        List<Submission> candidates = new ArrayList<>();
        candidates.addAll(queryProjectionPartition("MODERATION#PENDING", "SUBMISSION#", true));
        candidates.addAll(queryProjectionPartition("MODERATION#FAILED", "SUBMISSION#", true));
        Instant staleBefore = Instant.now().minusSeconds(30 * 60L);
        candidates.addAll(queryProjectionPartition("MODERATION#PROCESSING", "SUBMISSION#", true).stream()
                .filter(submission -> submission.getModerationStartedAt() != null
                        && submission.getModerationStartedAt().toInstant().isBefore(staleBefore))
                .toList());
        List<String> ids = candidates.stream()
                .filter(submission -> hasText(submission.getMediaUrls()))
                .sorted(Comparator.comparing(Submission::getId))
                .map(Submission::getId).distinct().toList();
        return slice(ids, pageable);
    }

    public int claimForModeration(String submissionId) {
        try {
            Submission submission = findById(submissionId).orElse(null);
            if (submission == null || !hasText(submission.getMediaUrls())) return 0;
            String status = normalizedStatus(submission.getModerationStatus());
            boolean stale = Submission.MODERATION_PROCESSING.equals(status)
                    && submission.getModerationStartedAt() != null
                    && submission.getModerationStartedAt().toInstant().isBefore(Instant.now().minusSeconds(30 * 60L));
            if (!Submission.MODERATION_PENDING.equals(status) && !Submission.MODERATION_FAILED.equals(status) && !stale) return 0;
            submission.setModerationStatus(Submission.MODERATION_PROCESSING);
            submission.setModerationStartedAt(Timestamp.from(Instant.now()));
            submission.setModerationError(null);
            save(submission);
            return 1;
        } catch (DynamoDomainStore.OptimisticWriteException conflict) {
            return 0;
        }
    }

    public List<SubmissionPreviewProjection> findSubmissionPreviews(
            List<String> challengeIds, String profileUserId, String viewerId
    ) {
        List<SubmissionPreviewProjection> previews = new ArrayList<>();
        for (String challengeId : challengeIds.stream().filter(SubmissionRepository::hasText).distinct().toList()) {
            DynamoPage<Submission> page = profileUserId == null
                    ? findVisibleByChallengePage(
                            challengeId, null, viewerId, "newest", null,
                            PREVIEW_VISIBLE_CANDIDATES, Map.of()
                    )
                    : findActiveByUserAndChallengePage(
                            profileUserId,
                            challengeId,
                            viewerId,
                            PREVIEW_VISIBLE_CANDIDATES,
                            Map.of()
                    );
            List<Submission> imageSubmissions = page.items().stream()
                    .filter(submission -> hasText(submission.getMediaUrls()))
                    .filter(submission -> IMAGE_MEDIA.matcher(submission.getMediaUrls()).find())
                    .toList();
            long total = imageSubmissions.size();
            boolean hasMore = page.hasMore();
            previews.add(new SubmissionPreview(
                    challengeId,
                    null,
                    null,
                    null,
                    null,
                    total,
                    0L,
                    !hasMore,
                    hasMore
            ));
            for (int index = 0; index < Math.min(4, imageSubmissions.size()); index++) {
                Submission submission = imageSubmissions.get(index);
                previews.add(new SubmissionPreview(
                        submission.getChallengeId(),
                        submission.getId(),
                        submission.getUserId(),
                        submission.getMediaUrls(),
                        submissionInstant(submission),
                        total,
                        (long) index + 1,
                        !hasMore,
                        hasMore
                ));
            }
        }
        return List.copyOf(previews);
    }

    public DynamoPage<Submission> findActiveByUserTimelinePage(
            String userId,
            int limit,
            String continuationSortKey
    ) {
        String partition = "USER#" + requireText(userId, "userId");
        return store.query(
                partition,
                "SUBMISSION#ACTIVE#",
                limit,
                FeedTimelineRepository.continuationKey(partition, continuationSortKey),
                true,
                Submission.class,
                true
        );
    }

    public List<Submission> findPendingFeedTimelineFanouts(int limit) {
        return store.query(
                FeedTimelineRepository.PENDING_SUBMISSION_PK,
                FeedTimelineRepository.PENDING_ITEM_PREFIX,
                limit,
                Map.of(),
                true,
                Submission.class,
                true
        ).items();
    }

    /**
     * Hydrates and authorizes one bounded durable-timeline candidate batch.
     * Fixed-key relationship checks are batch-read so stale entries cannot
     * bypass current follow, block, challenge, membership, or moderation state.
     */
    public List<Submission> findVisibleFollowingTimelineSubmissions(
            String viewerUserId,
            Collection<String> orderedSubmissionIds
    ) {
        return findVisibleTimelineSubmissions(viewerUserId, orderedSubmissionIds, true);
    }

    /** Re-authorizes a bounded public-feed candidate batch against current state. */
    public List<Submission> findVisiblePublicTimelineSubmissions(
            String viewerUserId,
            Collection<String> orderedSubmissionIds
    ) {
        return findVisibleTimelineSubmissions(viewerUserId, orderedSubmissionIds, false);
    }

    private List<Submission> findVisibleTimelineSubmissions(
            String viewerUserId,
            Collection<String> orderedSubmissionIds,
            boolean requireActiveFollow
    ) {
        if (!hasText(viewerUserId)
                || orderedSubmissionIds == null || orderedSubmissionIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = orderedSubmissionIds.stream()
                .filter(SubmissionRepository::hasText)
                .distinct()
                .toList();
        Map<String, Submission> byId = findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        Submission::getId,
                        submission -> submission,
                        (left, ignored) -> left,
                        LinkedHashMap::new
                ));
        Set<String> authors = byId.values().stream()
                .map(Submission::getUserId)
                .filter(SubmissionRepository::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> followedAuthors;
        if (requireActiveFollow) {
            followedAuthors = new LinkedHashSet<>(
                    followRepository.findActiveFollowedCandidateIds(viewerUserId, authors)
            );
            followedAuthors.add(viewerUserId);
        } else {
            followedAuthors = authors;
        }
        Set<String> blockedAuthors = userBlockRepository.findActiveBlockedCounterpartIds(
                viewerUserId,
                authors
        );

        Set<String> challengeIds = byId.values().stream()
                .filter(submission -> !viewerUserId.equals(submission.getUserId()))
                .map(Submission::getChallengeId)
                .filter(SubmissionRepository::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Challenge> challenges = challengeRepository.findAllById(challengeIds).stream()
                .collect(java.util.stream.Collectors.toMap(Challenge::getId, challenge -> challenge));
        Set<String> privateChallengeIds = byId.values().stream()
                .filter(submission -> !viewerUserId.equals(submission.getUserId()))
                .filter(submission -> Boolean.TRUE.equals(submission.getIsPrivate())
                        || Optional.ofNullable(challenges.get(submission.getChallengeId()))
                        .map(Challenge::getIsPrivate)
                        .map(Boolean.TRUE::equals)
                        .orElse(false))
                .map(Submission::getChallengeId)
                .filter(SubmissionRepository::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> privateMemberships = userChallengeRepository.findActiveMembershipChallengeIds(
                viewerUserId,
                privateChallengeIds
        );

        return ids.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .filter(SubmissionRepository::active)
                .filter(submission -> Submission.MODERATION_PASSED.equals(
                        normalizedStatus(submission.getModerationStatus())
                ))
                .filter(submission -> !requireActiveFollow
                        || followedAuthors.contains(submission.getUserId()))
                .filter(submission -> viewerUserId.equals(submission.getUserId())
                        || !blockedAuthors.contains(submission.getUserId()))
                .filter(submission -> {
                    if (viewerUserId.equals(submission.getUserId())) {
                        return true;
                    }
                    Challenge challenge = challenges.get(submission.getChallengeId());
                    if (challenge == null || !Boolean.TRUE.equals(challenge.getActive())) {
                        return false;
                    }
                    boolean privateContent = Boolean.TRUE.equals(submission.getIsPrivate())
                            || Boolean.TRUE.equals(challenge.getIsPrivate());
                    return !privateContent
                            || privateMemberships.contains(submission.getChallengeId());
                })
                .toList();
    }

    /**
     * Bounded multiway merge over sharded public-feed partitions. Trending
     * reads only the two ISO-week buckets that can intersect the fixed
     * seven-day window; explore reads the eight all-time chronology shards.
     */
    public ShardedSubmissionPage findPublicFeedPage(
            String viewerId,
            PublicFeedOrder order,
            Instant asOf,
            int limit,
            Map<String, Map<String, AttributeValue>> suppliedState
    ) {
        if (!hasText(viewerId)) {
            return ShardedSubmissionPage.empty();
        }
        PublicFeedOrder resolvedOrder = Objects.requireNonNull(order, "order");
        Instant resolvedAsOf = Objects.requireNonNull(asOf, "asOf");
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<PublicFeedShard> shards = publicFeedShards(resolvedOrder, resolvedAsOf);
        Map<String, Map<String, AttributeValue>> state = new LinkedHashMap<>();
        for (PublicFeedShard shard : shards) {
            state.put(
                    shard.name(),
                    suppliedState == null
                            ? Map.of()
                            : suppliedState.getOrDefault(shard.name(), Map.of())
            );
        }

        List<Submission> visible = new ArrayList<>(boundedLimit);
        int examined = 0;
        Instant trendingCutoff = resolvedAsOf.minusSeconds(7L * 24L * 60L * 60L);
        Map<String, ArrayDeque<PublicFeedCandidate>> buffers = new LinkedHashMap<>();
        Map<String, Map<String, AttributeValue>> fetchCursors = new LinkedHashMap<>();
        Map<String, Boolean> exhausted = new LinkedHashMap<>();
        PriorityQueue<PublicFeedCandidate> heads = new PriorityQueue<>(Comparator
                .comparing(PublicFeedCandidate::sortKey)
                .thenComparing(PublicFeedCandidate::shardName));

        for (PublicFeedShard shard : shards) {
            if (done(state.getOrDefault(shard.name(), Map.of()))) {
                continue;
            }
            ArrayDeque<PublicFeedCandidate> buffer = new ArrayDeque<>();
            buffers.put(shard.name(), buffer);
            fetchCursors.put(
                    shard.name(),
                    queryCursor(state.getOrDefault(shard.name(), Map.of()))
            );
            exhausted.put(shard.name(), false);
            refillPublicFeedShard(
                    shard,
                    resolvedOrder,
                    buffer,
                    fetchCursors,
                    exhausted
            );
            if (buffer.isEmpty()) {
                state.put(shard.name(), doneState());
            } else {
                heads.add(buffer.peekFirst());
            }
        }

        while (visible.size() < boundedLimit
                && examined < MAX_PUBLIC_FEED_CANDIDATES
                && !heads.isEmpty()) {
            int remainingBudget = MAX_PUBLIC_FEED_CANDIDATES - examined;
            int candidateBatchSize = Math.min(100, remainingBudget);
            List<MergedPublicCandidate> candidates = new ArrayList<>(candidateBatchSize);
            while (candidates.size() < candidateBatchSize && !heads.isEmpty()) {
                PublicFeedCandidate candidate = heads.remove();
                ArrayDeque<PublicFeedCandidate> buffer = buffers.get(candidate.shardName());
                PublicFeedCandidate removed = buffer.removeFirst();
                if (!removed.equals(candidate)) {
                    throw new IllegalStateException("Public feed shard head changed during merge");
                }
                if (buffer.isEmpty() && !exhausted.get(candidate.shardName())) {
                    PublicFeedShard shard = shards.stream()
                            .filter(value -> value.name().equals(candidate.shardName()))
                            .findFirst()
                            .orElseThrow();
                    refillPublicFeedShard(
                            shard,
                            resolvedOrder,
                            buffer,
                            fetchCursors,
                            exhausted
                    );
                }
                boolean exhaustsShard = buffer.isEmpty()
                        && exhausted.get(candidate.shardName());
                candidates.add(new MergedPublicCandidate(candidate, exhaustsShard));
                if (!buffer.isEmpty()) {
                    heads.add(buffer.peekFirst());
                }
            }

            List<String> candidateIds = candidates.stream()
                    .map(MergedPublicCandidate::candidate)
                    .map(candidate -> candidate.submission().getId())
                    .toList();
            Map<String, Submission> visibleById = findVisiblePublicTimelineSubmissions(
                    viewerId,
                    candidateIds
            ).stream()
                    .filter(submission -> resolvedOrder != PublicFeedOrder.TRENDING
                            || createdInstant(submission).isAfter(trendingCutoff))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Submission::getId,
                            submission -> submission,
                            (left, ignored) -> left
                    ));

            for (MergedPublicCandidate merged : candidates) {
                if (examined >= MAX_PUBLIC_FEED_CANDIDATES
                        || visible.size() >= boundedLimit) {
                    break;
                }
                PublicFeedCandidate candidate = merged.candidate();
                examined++;
                state.put(
                        candidate.shardName(),
                        merged.exhaustsShard()
                                ? doneState()
                                : FeedTimelineRepository.continuationKey(
                                        candidate.partitionKey(),
                                        candidate.sortKey()
                                )
                );
                Submission authorized = visibleById.get(candidate.submission().getId());
                if (authorized != null) {
                    visible.add(authorized);
                }
            }
        }

        boolean hasMore = shards.stream()
                .anyMatch(shard -> !done(state.getOrDefault(shard.name(), Map.of())));
        return new ShardedSubmissionPage(visible, state, hasMore);
    }

    private void refillPublicFeedShard(
            PublicFeedShard shard,
            PublicFeedOrder order,
            ArrayDeque<PublicFeedCandidate> buffer,
            Map<String, Map<String, AttributeValue>> fetchCursors,
            Map<String, Boolean> exhausted
    ) {
        DynamoPage<Submission> page = store.query(
                shard.partitionKey(),
                "SUBMISSION#",
                PUBLIC_FEED_PREFETCH_SIZE,
                fetchCursors.getOrDefault(shard.name(), Map.of()),
                true,
                Submission.class,
                true
        );
        page.items().stream()
                .map(submission -> new PublicFeedCandidate(
                        shard.name(),
                        shard.partitionKey(),
                        publicFeedSortKey(order, submission),
                        submission
                ))
                .forEach(buffer::addLast);
        fetchCursors.put(shard.name(), page.lastEvaluatedKey());
        exhausted.put(shard.name(), !page.hasMore());
    }

    public List<Submission> findByUserChallengeAndDay(String userId, String challengeId, Integer dayNumber) {
        String prefix = "SUBMISSION#CHALLENGE#" + requireText(challengeId, "challengeId")
                + "#DAY#" + paddedDay(dayNumber) + '#';
        return queryProjectionPartition("USER#" + requireText(userId, "userId"), prefix, false);
    }

    public List<Submission> findByUserIdAndChallengeId(String userId, String challengeId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"),
                "SUBMISSION#CHALLENGE#" + requireText(challengeId, "challengeId") + '#', false).stream()
                .filter(SubmissionRepository::active).toList();
    }

    public Page<Submission> findByUserIdAndChallengeId(String userId, String challengeId, Pageable pageable) {
        return page(findByUserIdAndChallengeId(userId, challengeId), pageable);
    }

    public DynamoPage<Submission> findActiveByUserAndChallengePage(
            String userId,
            String challengeId,
            String viewerId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return filteredProjectionPage(
                "SUBMISSION#USER_CHALLENGE#" + requireText(userId, "userId")
                        + '#' + requireText(challengeId, "challengeId"),
                "SUBMISSION#ACTIVE#",
                true,
                viewerId,
                null,
                false,
                limit,
                exclusiveStartKey
        );
    }

    public List<Submission> findActiveByChallengeId(String challengeId) {
        return queryProjectionPartition("CHALLENGE#" + requireText(challengeId, "challengeId"),
                "SUBMISSION#ACTIVE#", false);
    }

    public Page<Submission> findActiveByChallengeId(String challengeId, Pageable pageable) {
        return page(findActiveByChallengeId(challengeId), pageable);
    }

    public Page<Submission> findVisibleByChallengeIdAndDayNumber(
            String challengeId, Integer dayNumber, String viewerId, Pageable pageable
    ) {
        return findVisibleByChallengeIdAndDayNumberNewest(challengeId, dayNumber, viewerId, null, pageable);
    }

    public Page<Submission> findVisibleByChallengeIdAndDayNumberNewest(
            String challengeId, Integer dayNumber, String viewerId, String participantQuery, Pageable pageable
    ) {
        return visiblePage(challengeId, dayNumber, viewerId, participantQuery, NEWEST, pageable);
    }

    public Page<Submission> findVisibleByChallengeIdAndDayNumberOldest(
            String challengeId, Integer dayNumber, String viewerId, String participantQuery, Pageable pageable
    ) {
        return visiblePage(challengeId, dayNumber, viewerId, participantQuery, OLDEST, pageable);
    }

    public Page<Submission> findVisibleByChallengeIdAndDayNumberMostGlories(
            String challengeId, Integer dayNumber, String viewerId, String participantQuery, Pageable pageable
    ) {
        return visiblePage(challengeId, dayNumber, viewerId, participantQuery, MOST_GLORIES, pageable);
    }

    public Page<Submission> findVisibleByChallengeIdNewest(
            String challengeId, String viewerId, String participantQuery, Pageable pageable
    ) {
        return visiblePage(challengeId, null, viewerId, participantQuery, NEWEST, pageable);
    }

    public Page<Submission> findVisibleByChallengeIdOldest(
            String challengeId, String viewerId, String participantQuery, Pageable pageable
    ) {
        return visiblePage(challengeId, null, viewerId, participantQuery, OLDEST, pageable);
    }

    public Page<Submission> findVisibleByChallengeIdMostGlories(
            String challengeId, String viewerId, String participantQuery, Pageable pageable
    ) {
        return visiblePage(challengeId, null, viewerId, participantQuery, MOST_GLORIES, pageable);
    }

    public List<Submission> findPublicActiveByChallengeId(String challengeId, String viewerId) {
        return visibleForChallenge(challengeId, viewerId, null).stream().sorted(NEWEST).toList();
    }

    public List<Submission> findActiveByUserId(String userId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"), "SUBMISSION#ACTIVE#", false);
    }

    public Page<Submission> findActiveByUserId(String userId, Pageable pageable) {
        return page(findActiveByUserId(userId), pageable);
    }

    public DynamoPage<Submission> findActiveByUserPage(
            String userId,
            String viewerId,
            boolean publicOnly,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return filteredProjectionPage(
                "USER#" + requireText(userId, "userId"),
                "SUBMISSION#ACTIVE#",
                true,
                viewerId,
                null,
                publicOnly,
                limit,
                exclusiveStartKey
        );
    }

    public DynamoPage<Submission> findVisibleByChallengePage(
            String challengeId,
            Integer dayNumber,
            String viewerId,
            String sortBy,
            String participantQuery,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        String normalizedSort = hasText(sortBy) ? sortBy.strip().toLowerCase(Locale.ROOT) : "newest";
        String partitionKey;
        String sortKeyPrefix;
        boolean ascending;
        if (dayNumber != null) {
            partitionKey = "CHALLENGE#" + requireText(challengeId, "challengeId");
            String dayPrefix = "SUBMISSION#DAY#" + paddedDay(dayNumber);
            if ("glories".equals(normalizedSort)) {
                sortKeyPrefix = dayPrefix + "#GLORY_RANK#";
                ascending = true;
            } else {
                sortKeyPrefix = dayPrefix + "#NEWEST#";
                ascending = !"oldest".equals(normalizedSort);
            }
        } else if ("glories".equals(normalizedSort)) {
            partitionKey = "CHALLENGE#" + requireText(challengeId, "challengeId") + "#ACTIVE_GLORY";
            sortKeyPrefix = "SUBMISSION#";
            ascending = true;
        } else {
            partitionKey = "CHALLENGE#" + requireText(challengeId, "challengeId");
            sortKeyPrefix = "SUBMISSION#ACTIVE#";
            ascending = !"oldest".equals(normalizedSort);
        }
        return filteredProjectionPage(
                partitionKey,
                sortKeyPrefix,
                ascending,
                viewerId,
                participantQuery,
                false,
                limit,
                exclusiveStartKey
        );
    }

    public Integer countActiveByUserId(String userId) {
        return findActiveByUserId(userId).size();
    }

    public List<Submission> findByUserId(String userId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"), "SUBMISSION#ALL#", false);
    }

    public List<Submission> findPublicByUserId(String userId) {
        return findActiveByUserId(userId).stream().filter(submission -> !Boolean.TRUE.equals(submission.getIsPrivate())).toList();
    }

    public Optional<Submission> findByIdAndUserId(String submissionId, String userId) {
        return findById(submissionId).filter(submission -> userId.equals(submission.getUserId()));
    }

    public List<Submission> findBySubmissionDate(LocalDate date) {
        return queryProjectionPartition("SUBMISSION#DATE#" + date, "SUBMISSION#", false);
    }

    public List<Submission> findRecentByChallengeId(String challengeId, int limit) {
        return findActiveByChallengeId(challengeId).stream()
                .filter(submission -> !Boolean.TRUE.equals(submission.getIsPrivate()))
                .sorted(NEWEST).limit(Math.max(0, limit)).toList();
    }

    public Long countActiveByUserIdAndChallengeId(String userId, String challengeId) {
        return (long) findByUserIdAndChallengeId(userId, challengeId).size();
    }

    public Long countActiveByChallengeId(String challengeId) {
        return (long) findActiveByChallengeId(challengeId).size();
    }

    public List<Submission> findTrendingByChallengeId(String challengeId, int limit) {
        return findActiveByChallengeId(challengeId).stream()
                .filter(submission -> !Boolean.TRUE.equals(submission.getIsPrivate()))
                .sorted(MOST_GLORIES).limit(Math.max(0, limit)).toList();
    }

    public List<Submission> findByChallengeIdIn(List<String> challengeIds) {
        return challengeIds.stream().filter(SubmissionRepository::hasText).distinct()
                .flatMap(challengeId -> findActiveByChallengeId(challengeId).stream()).sorted(NEWEST).toList();
    }

    public List<Submission> findByUserIdAndDateRange(String userId, LocalDate startDate, LocalDate endDate) {
        return findActiveByUserId(userId).stream().filter(submission -> {
            LocalDate date = submissionInstant(submission).atZone(ZoneOffset.UTC).toLocalDate();
            return !date.isBefore(startDate) && !date.isAfter(endDate);
        }).sorted(NEWEST).toList();
    }

    public boolean existsByUserChallengeAndDay(String userId, String challengeId, Integer dayNumber) {
        return !findByUserChallengeAndDay(userId, challengeId, dayNumber).isEmpty();
    }

    public List<PendingDailyChallengeProjection> findIncompleteDailyChallengesForUser(String userId, LocalDate localDate) {
        List<PendingDailyChallengeProjection> result = new ArrayList<>();
        for (UserChallenge membership : userChallengeRepository.findActiveByUserId(userId)) {
            if (Boolean.TRUE.equals(membership.getBanned()) || Boolean.TRUE.equals(membership.getCompleted())) continue;
            Challenge challenge = challengeRepository.findById(membership.getChallengeId()).orElse(null);
            if (challenge == null || !Boolean.TRUE.equals(challenge.getActive())
                    || !Challenge.MODE_DAILY.equalsIgnoreCase(challenge.getChallengeMode())
                    || challenge.getStartDate() == null || challenge.getNumberOfDays() == null) continue;
            int day = (int) (localDate.toEpochDay() - challenge.getStartDate().toEpochDay()) + 1;
            if (day < 1 || day > challenge.getNumberOfDays()
                    || existsByUserChallengeAndDay(userId, challenge.getId(), day)) continue;
            result.add(new PendingDailyChallenge(challenge.getId(), challenge.getTitle(), day));
        }
        return List.copyOf(result);
    }

    public List<Submission> findActiveByUserIdAndChallengeId(String userId, String challengeId) {
        return findByUserIdAndChallengeId(userId, challengeId).stream()
                .sorted(Comparator.comparing(Submission::getDayNumber, Comparator.nullsLast(Integer::compareTo))).toList();
    }

    public long countActiveByUserIdAndChallengeIdAndDayNumber(String userId, String challengeId, Integer dayNumber) {
        return findByUserChallengeAndDay(userId, challengeId, dayNumber).stream().filter(SubmissionRepository::active).count();
    }

    public List<Submission> findByChallengeIdAndUserId(String challengeId, String userId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"),
                "SUBMISSION#CHALLENGE#" + requireText(challengeId, "challengeId") + '#', true).stream()
                .sorted(Comparator.comparing(Submission::getDayNumber, Comparator.nullsLast(Integer::compareTo))).toList();
    }

    public List<Submission> findByChallengeIdAndUserIdIn(String challengeId, List<String> userIds) {
        Set<String> users = Set.copyOf(userIds);
        return findActiveByChallengeId(challengeId).stream().filter(submission -> users.contains(submission.getUserId())).toList();
    }

    /**
     * Applies an update to the latest strongly consistent aggregate version.
     * This is the update path for moderation, privacy, and lifecycle changes so
     * stale service objects cannot overwrite concurrent interaction counters.
     */
    public Submission mutateExisting(String submissionId, Consumer<Submission> mutation) {
        if (!hasText(submissionId)) {
            throw new IllegalArgumentException("submissionId must not be blank");
        }
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            Optional<TransactionalDynamoWriter.Versioned<Submission>> versioned =
                    writer.loadVersioned(entityType(), submissionId, Submission.class);
            if (versioned.isEmpty()) {
                throw new IllegalArgumentException("Submission not found: " + submissionId);
            }
            Submission latest = versioned.get().value();
            boolean wasActive = active(latest);
            List<DynamoProjection> previousProjections = allProjections(latest);
            List<DynamoProjection> previousDaySlot = daySlotProjection(latest);
            mutation.accept(latest);
            if (!wasActive && active(latest)) {
                latest.setFeedTimelineFanoutCursor(null);
                latest.setFeedTimelineFanoutCompletedAt(null);
            }
            prepareForSave(latest);
            try {
                writer.save(
                        entityType(),
                        submissionId,
                        latest,
                        allProjections(latest),
                        previousProjections,
                        daySlotProjection(latest),
                        previousDaySlot,
                        false,
                        versioned.get().version()
                );
                return latest;
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        throw new DataIntegrityViolationException(
                "Concurrent submission update did not converge for " + submissionId,
                lastConflict
        );
    }

    public void updateFeedTimelineFanoutProgress(
            String submissionId,
            String continuationSortKey,
            boolean completed
    ) {
        mutateExisting(submissionId, submission -> {
            submission.setFeedTimelineFanoutCursor(completed ? null : continuationSortKey);
            submission.setFeedTimelineFanoutCompletedAt(
                    completed ? Timestamp.from(Instant.now()) : null
            );
        });
    }

    private DynamoPage<Submission> filteredProjectionPage(
            String partitionKey,
            String sortKeyPrefix,
            boolean ascending,
            String viewerId,
            String participantQuery,
            boolean publicOnly,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        Set<String> hiddenUserIds = new LinkedHashSet<>();
        Map<String, Optional<Challenge>> challenges = new HashMap<>();
        Map<String, Boolean> privateMemberships = new HashMap<>();
        Map<String, Boolean> participantMatches = new HashMap<>();
        List<Submission> visible = new ArrayList<>(boundedLimit);
        int inspectedCandidates = 0;
        Map<String, AttributeValue> continuation = exclusiveStartKey == null
                ? Map.of()
                : Map.copyOf(exclusiveStartKey);
        do {
            int queryLimit = Math.min(
                    Math.max(1, boundedLimit - visible.size()),
                    MAX_FILTERED_CANDIDATES - inspectedCandidates
            );
            DynamoPage<Submission> page = queryProjectionPage(
                    partitionKey,
                    sortKeyPrefix,
                    queryLimit,
                    continuation,
                    ascending
            );
            inspectedCandidates += page.items().size();
            if (viewerId != null && !page.items().isEmpty()) {
                hiddenUserIds.addAll(userBlockRepository.findActiveBlockedCounterpartIds(
                        viewerId,
                        page.items().stream()
                                .map(Submission::getUserId)
                                .filter(SubmissionRepository::hasText)
                                .distinct()
                                .toList()
                ));
            }
            for (Submission submission : page.items()) {
                if (publicOnly && Boolean.TRUE.equals(submission.getIsPrivate())) {
                    continue;
                }
                if (!visibleTo(
                        submission,
                        viewerId,
                        hiddenUserIds,
                        challenges,
                        privateMemberships
                )) {
                    continue;
                }
                if (hasText(participantQuery)
                        && !participantMatches.computeIfAbsent(
                                submission.getUserId(),
                                ignored -> participantMatches(submission.getUserId(), participantQuery)
                        )) {
                    continue;
                }
                visible.add(submission);
            }
            continuation = page.lastEvaluatedKey();
        } while (visible.size() < boundedLimit
                && inspectedCandidates < MAX_FILTERED_CANDIDATES
                && !continuation.isEmpty());
        return new DynamoPage<>(visible, continuation);
    }

    private boolean visibleTo(
            Submission submission,
            String viewerId,
            Set<String> hiddenUserIds,
            Map<String, Optional<Challenge>> challenges,
            Map<String, Boolean> privateMemberships
    ) {
        if (viewerId != null && viewerId.equals(submission.getUserId())) {
            return true;
        }
        if (viewerId != null && hiddenUserIds.contains(submission.getUserId())) {
            return false;
        }
        Optional<Challenge> challenge = challenges.computeIfAbsent(
                submission.getChallengeId(),
                challengeRepository::findById
        );
        if (challenge.isEmpty() || !Boolean.TRUE.equals(challenge.get().getActive())) {
            return false;
        }
        boolean privateContent = Boolean.TRUE.equals(challenge.get().getIsPrivate())
                || Boolean.TRUE.equals(submission.getIsPrivate());
        if (!privateContent) {
            return true;
        }
        if (viewerId == null) {
            return false;
        }
        return privateMemberships.computeIfAbsent(
                submission.getChallengeId(),
                challengeId -> userChallengeRepository
                        .findByUserIdAndChallengeId(viewerId, challengeId)
                        .filter(UserChallenge::getActive)
                        .filter(membership -> !Boolean.TRUE.equals(membership.getBanned()))
                        .isPresent()
        );
    }

    private Page<Submission> visiblePage(
            String challengeId, Integer dayNumber, String viewerId, String participantQuery,
            Comparator<Submission> comparator, Pageable pageable
    ) {
        List<Submission> submissions = visibleForChallenge(challengeId, viewerId, participantQuery).stream()
                .filter(submission -> dayNumber == null || dayNumber.equals(submission.getDayNumber()))
                .sorted(comparator).toList();
        return page(submissions, pageable);
    }

    private List<Submission> visibleForChallenge(String challengeId, String viewerId, String participantQuery) {
        return findActiveByChallengeId(challengeId).stream().filter(submission -> visibleTo(submission, viewerId))
                .filter(submission -> participantMatches(submission.getUserId(), participantQuery)).toList();
    }

    private boolean visibleTo(Submission submission, String viewerId) {
        if (viewerId != null && viewerId.equals(submission.getUserId())) return true;
        if (viewerId != null && userBlockRepository.existsActiveBlockBetween(viewerId, submission.getUserId())) return false;
        Challenge challenge = challengeRepository.findById(submission.getChallengeId()).orElse(null);
        if (challenge == null || !Boolean.TRUE.equals(challenge.getActive())) return false;
        boolean privateContent = Boolean.TRUE.equals(challenge.getIsPrivate()) || Boolean.TRUE.equals(submission.getIsPrivate());
        if (!privateContent) return true;
        if (viewerId == null) return false;
        return userChallengeRepository.findByUserIdAndChallengeId(viewerId, submission.getChallengeId())
                .filter(UserChallenge::getActive)
                .filter(membership -> !Boolean.TRUE.equals(membership.getBanned())).isPresent();
    }

    private boolean participantMatches(String userId, String participantQuery) {
        if (!hasText(participantQuery)) return true;
        String prefix = participantQuery.strip().toLowerCase(Locale.ROOT);
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return false;
        return normalize(user.getUsername()).startsWith(prefix) || normalize(user.getName()).startsWith(prefix);
    }

    private static List<PublicFeedShard> publicFeedShards(
            PublicFeedOrder order,
            Instant asOf
    ) {
        List<PublicFeedShard> shards = new ArrayList<>();
        if (order == PublicFeedOrder.EXPLORE) {
            for (int shard = 0; shard < DynamoAccessPatterns.PUBLIC_FEED_SHARDS; shard++) {
                String name = String.format(Locale.ROOT, "p%02d", shard);
                shards.add(new PublicFeedShard(
                        name,
                        String.format(Locale.ROOT, "FEED#PUBLIC#%02d", shard)
                ));
            }
            return List.copyOf(shards);
        }

        LinkedHashSet<String> buckets = new LinkedHashSet<>();
        buckets.add(isoWeekBucket(asOf));
        buckets.add(isoWeekBucket(asOf.minusSeconds(7L * 24L * 60L * 60L)));
        for (String bucket : buckets) {
            for (int shard = 0; shard < DynamoAccessPatterns.PUBLIC_FEED_SHARDS; shard++) {
                String name = String.format(Locale.ROOT, "t%s-%02d", bucket, shard);
                shards.add(new PublicFeedShard(
                        name,
                        String.format(
                                Locale.ROOT,
                                "FEED#TRENDING#%s#%02d",
                                bucket,
                                shard
                        )
                ));
            }
        }
        return List.copyOf(shards);
    }

    private static String publicFeedSortKey(PublicFeedOrder order, Submission submission) {
        String chronology = reverseTime(createdInstant(submission)) + '#' + submission.getId();
        if (order == PublicFeedOrder.EXPLORE) {
            return "SUBMISSION#" + chronology;
        }
        return "SUBMISSION#"
                + reverseNumber(number(submission.getGloriesCount())) + '#'
                + reverseNumber(number(submission.getCommentsCount())) + '#'
                + chronology;
    }

    private static String isoWeekBucket(Instant instant) {
        LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate();
        return String.format(
                Locale.ROOT,
                "%04d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR),
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        );
    }

    private static boolean done(Map<String, AttributeValue> state) {
        AttributeValue marker = state == null ? null : state.get(CURSOR_DONE);
        return marker != null && "1".equals(marker.s());
    }

    private static Map<String, AttributeValue> doneState() {
        return Map.of(CURSOR_DONE, AttributeValue.fromS("1"));
    }

    private static Map<String, AttributeValue> queryCursor(
            Map<String, AttributeValue> state
    ) {
        return done(state) ? Map.of() : state;
    }

    private <T> List<T> slice(List<T> values, Pageable pageable) {
        int start = Math.toIntExact(Math.min(pageable.getOffset(), values.size()));
        int end = Math.min(start + pageable.getPageSize(), values.size());
        return List.copyOf(values.subList(start, end));
    }

    private Page<Submission> page(List<Submission> values, Pageable pageable) {
        int start = Math.toIntExact(Math.min(pageable.getOffset(), values.size()));
        int end = Math.min(start + pageable.getPageSize(), values.size());
        return new PageImpl<>(values.subList(start, end), pageable, values.size());
    }

    @Override protected String id(Submission entity) { return entity.getId(); }
    @Override protected void assignId(Submission entity, String id) { entity.setId(id); }

    @Override
    protected Collection<DynamoProjection> projections(Submission entity) {
        String id = entity.getId();
        String userId = entity.getUserId();
        String challengeId = entity.getChallengeId();
        String chronology = reverseTime(createdInstant(entity)) + '#' + id;
        String day = paddedDay(entity.getDayNumber());
        List<DynamoProjection> result = new ArrayList<>();
        if (hasText(userId)) {
            result.add(DynamoProjection.of("USER#" + userId, "SUBMISSION#ALL#" + chronology));
            result.add(DynamoProjection.of("USER#" + userId,
                    "SUBMISSION#CHALLENGE#" + challengeId + "#DAY#" + day + '#' + chronology));
        }
        if (hasText(challengeId)) result.add(DynamoProjection.of("CHALLENGE#" + challengeId, "SUBMISSION#ALL#" + chronology));
        result.add(DynamoProjection.of("MODERATION#" + normalizedStatus(entity.getModerationStatus()), "SUBMISSION#" + chronology));
        if (active(entity)) {
            if (hasText(userId)) {
                result.add(DynamoProjection.of("USER#" + userId, "SUBMISSION#ACTIVE#" + chronology));
                if (hasText(challengeId)) {
                    result.add(DynamoProjection.of(
                            "SUBMISSION#USER_CHALLENGE#" + userId + '#' + challengeId,
                            "SUBMISSION#ACTIVE#" + chronology
                    ));
                }
            }
            if (hasText(challengeId)) {
                result.add(DynamoProjection.of("CHALLENGE#" + challengeId, "SUBMISSION#ACTIVE#" + chronology));
                result.add(DynamoProjection.of(
                        "CHALLENGE#" + challengeId + "#ACTIVE_GLORY",
                        "SUBMISSION#" + reverseNumber(number(entity.getGloriesCount())) + '#' + chronology
                ));
                result.add(DynamoProjection.of("CHALLENGE#" + challengeId,
                        "SUBMISSION#DAY#" + day + "#NEWEST#" + chronology));
                result.add(DynamoProjection.of("CHALLENGE#" + challengeId,
                        "SUBMISSION#DAY#" + day + "#GLORY#" + paddedNumber(number(entity.getGloriesCount())) + '#' + chronology));
                result.add(DynamoProjection.of("CHALLENGE#" + challengeId,
                        "SUBMISSION#DAY#" + day + "#GLORY_RANK#"
                                + reverseNumber(number(entity.getGloriesCount())) + '#' + chronology));
            }
            LocalDate submissionDate = submissionInstant(entity).atZone(ZoneOffset.UTC).toLocalDate();
            result.add(DynamoProjection.of("SUBMISSION#DATE#" + submissionDate, "SUBMISSION#" + chronology));
            if (!Boolean.TRUE.equals(entity.getIsPrivate())) {
                int shard = DynamoAccessPatterns.stableShard(id, DynamoAccessPatterns.PUBLIC_FEED_SHARDS);
                result.add(DynamoProjection.of(String.format(Locale.ROOT, "FEED#PUBLIC#%02d", shard),
                        "SUBMISSION#" + chronology));
                result.add(DynamoProjection.of(
                        String.format(
                                Locale.ROOT,
                                "FEED#TRENDING#%s#%02d",
                                isoWeekBucket(createdInstant(entity)),
                                shard
                        ),
                        publicFeedSortKey(PublicFeedOrder.TRENDING, entity)
                ));
            }
            if (entity.getFeedTimelineFanoutCompletedAt() == null) {
                result.add(DynamoProjection.of(
                        FeedTimelineRepository.PENDING_SUBMISSION_PK,
                        FeedTimelineRepository.pendingSortKey(createdInstant(entity), id)
                ));
            }
        }
        return result;
    }

    public enum PublicFeedOrder {
        EXPLORE,
        TRENDING
    }

    public record ShardedSubmissionPage(
            List<Submission> items,
            Map<String, Map<String, AttributeValue>> shardState,
            boolean hasMore
    ) {
        public ShardedSubmissionPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (shardState == null || shardState.isEmpty()) {
                shardState = Map.of();
            } else {
                Map<String, Map<String, AttributeValue>> copy = new LinkedHashMap<>();
                shardState.forEach((name, key) -> copy.put(
                        name,
                        key == null || key.isEmpty() ? Map.of() : Map.copyOf(key)
                ));
                shardState = Map.copyOf(copy);
            }
        }

        private static ShardedSubmissionPage empty() {
            return new ShardedSubmissionPage(List.of(), Map.of(), false);
        }
    }

    private record PublicFeedShard(String name, String partitionKey) { }

    private record PublicFeedCandidate(
            String shardName,
            String partitionKey,
            String sortKey,
            Submission submission
    ) { }

    private record MergedPublicCandidate(
            PublicFeedCandidate candidate,
            boolean exhaustsShard
    ) { }

    public interface PendingDailyChallengeProjection {
        String getChallengeId();
        String getChallengeTitle();
        Integer getDayNumber();
    }

    public static final class SubmissionDayLimitExceededException extends RuntimeException {
        public SubmissionDayLimitExceededException(int limit, Throwable cause) {
            super("Active submission limit of " + limit + " reached for this challenge day", cause);
        }
    }

    private record PendingDailyChallenge(String challengeId, String challengeTitle, Integer dayNumber)
            implements PendingDailyChallengeProjection {
        @Override public String getChallengeId() { return challengeId; }
        @Override public String getChallengeTitle() { return challengeTitle; }
        @Override public Integer getDayNumber() { return dayNumber; }
    }

    private record SubmissionPreview(
            String challengeId, String submissionId, String userId, String mediaUrls,
            Instant submissionTime, Long totalCount, Long rowNumber,
            Boolean totalCountExact, Boolean hasMore
    ) implements SubmissionPreviewProjection {
        @Override public String getChallengeId() { return challengeId; }
        @Override public String getSubmissionId() { return submissionId; }
        @Override public String getUserId() { return userId; }
        @Override public String getMediaUrls() { return mediaUrls; }
        @Override public Instant getSubmissionTime() { return submissionTime; }
        @Override public Long getTotalCount() { return totalCount; }
        @Override public Long getRowNumber() { return rowNumber; }
        @Override public Boolean getTotalCountExact() { return totalCountExact; }
        @Override public Boolean getHasMore() { return hasMore; }
    }

    private static boolean active(Submission submission) { return Boolean.TRUE.equals(submission.getActive()); }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
    private static String normalizedStatus(String status) {
        return hasText(status) ? status.strip().toUpperCase(Locale.ROOT) : Submission.MODERATION_PENDING;
    }
    private static long number(Long value) { return value == null ? 0L : value; }
    private static String paddedDay(Integer day) {
        return String.format(Locale.ROOT, "%010d", day == null ? 0 : Math.max(0, day));
    }
    private static String paddedNumber(long value) {
        return String.format(Locale.ROOT, "%020d", Math.max(0, value));
    }
    private static String reverseNumber(long value) {
        return String.format(Locale.ROOT, "%020d", Long.MAX_VALUE - Math.max(0, value));
    }
    private static String reverseTime(Instant instant) {
        return String.format(Locale.ROOT, "%019d", Long.MAX_VALUE - Math.max(0, instant.toEpochMilli()));
    }
    private static Instant createdInstant(Submission submission) {
        return submission.getCreatedAt() != null ? submission.getCreatedAt().toInstant() : submissionInstant(submission);
    }
    private static Instant submissionInstant(Submission submission) {
        return submission.getSubmissionTime() == null ? Instant.EPOCH : submission.getSubmissionTime().toInstant();
    }
}
