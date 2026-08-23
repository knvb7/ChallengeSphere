package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.AbstractChallengeDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeRepositoryKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeTransactionalWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ConditionalKeyLease;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ChallengeRepository extends AbstractChallengeDynamoRepository<Challenge> {

    private static final int MAX_DATE_RANGE_DAYS = 3_660;
    // Stored CSV values remain complete. Only the first distinct normalized
    // values are indexed: 20 + 18 keeps the worst old-to-new projection
    // transition at 96 transaction actions (strictly below DynamoDB's 100).
    private static final int MAX_INDEXED_HASHTAGS = 20;
    private static final int MAX_INDEXED_CATEGORIES = 18;
    private static final int TAG_SHARD_PREFETCH = 10;
    private static final int MAX_TAG_PROJECTION_CANDIDATES = 500;
    private static final String CURSOR_DONE = "__done";
    private static final Comparator<Challenge> RECENT_FIRST = Comparator
            .comparing(Challenge::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Challenge::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();

    private final Clock clock;

    public ChallengeRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            Clock clock
    ) {
        super(store, writer, leases, "CHALLENGE", Challenge.class);
        this.clock = clock;
    }

    public ConditionalKeyLease.Lease acquireChallengeLease(String challengeId) {
        return leases.acquireLease(
                entityType(),
                ChallengeRepositoryKeys.require(challengeId, "challengeId")
        );
    }

    public List<Challenge> findByCreatorId(String creatorId) {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.challengeCreatorPk(creatorId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    /** Newest-first creator page backed by a real DynamoDB continuation key. */
    public DynamoPage<Challenge> findByCreatorIdPage(
            String creatorId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return queryProjectionPage(
                ChallengeRepositoryKeys.challengeCreatorPk(creatorId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                limit,
                exclusiveStartKey,
                false
        );
    }

    public List<Challenge> findPublicActiveByCreatorId(String creatorId) {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.join("CHALLENGE", "CREATOR", creatorId, "PUBLIC_ACTIVE"),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    /** Newest-first public-and-active creator page with keyset pagination. */
    public DynamoPage<Challenge> findPublicActiveByCreatorIdPage(
            String creatorId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return queryProjectionPage(
                ChallengeRepositoryKeys.join("CHALLENGE", "CREATOR", creatorId, "PUBLIC_ACTIVE"),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                limit,
                exclusiveStartKey,
                false
        );
    }

    public List<Challenge> findByCurrentOwnerId(String ownerId) {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.challengeOwnerPk(ownerId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    /** Newest-first current-owner page backed by a real DynamoDB continuation key. */
    public DynamoPage<Challenge> findByCurrentOwnerIdPage(
            String ownerId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return queryProjectionPage(
                ChallengeRepositoryKeys.challengeOwnerPk(ownerId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                limit,
                exclusiveStartKey,
                false
        );
    }

    public List<Challenge> findAllActiveChallenges() {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.challengeStatePk("ACTIVE"),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    public List<Challenge> findActiveByIds(List<String> challengeIds) {
        if (challengeIds == null || challengeIds.isEmpty()) {
            return List.of();
        }
        return store.batchGet("CHALLENGE", challengeIds, Challenge.class, true).stream()
                .filter(challenge -> Boolean.TRUE.equals(challenge.getActive()))
                .sorted(RECENT_FIRST)
                .toList();
    }

    public List<Challenge> findAllPublicChallenges() {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.challengeStatePk("PUBLIC_ACTIVE"),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    public List<Challenge> findAllPrivateChallenges() {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.challengeStatePk("PRIVATE_ACTIVE"),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    public List<Challenge> findRecentPublicChallenges(int limit) {
        return recentPublicWindow(limit, 0);
    }

    public List<Challenge> findRecentPublicChallengesPaginated(int limit, int offset) {
        return recentPublicWindow(limit, offset);
    }

    /** Newest-first page from the existing public-and-active projection. */
    public DynamoPage<Challenge> findPublicActivePage(
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return queryProjectionPage(
                ChallengeRepositoryKeys.challengeStatePk("PUBLIC_ACTIVE"),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                limit,
                exclusiveStartKey,
                false
        );
    }

    public long countPublicActiveChallenges() {
        return queryProjectionPartition(
                ChallengeRepositoryKeys.challengeStatePk("PUBLIC_ACTIVE"),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                true
        ).size();
    }

    public List<Challenge> findByStartDate(LocalDate startDate) {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.challengeStartDatePk(startDate),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    public List<Challenge> findByStartDateBetween(LocalDate fromDate, LocalDate toDate) {
        ChallengeRepositoryKeys.require(fromDate, "fromDate");
        ChallengeRepositoryKeys.require(toDate, "toDate");
        long days = ChronoUnit.DAYS.between(fromDate, toDate);
        if (days < 0) {
            return List.of();
        }
        if (days > MAX_DATE_RANGE_DAYS) {
            throw new IllegalArgumentException("start-date range exceeds " + MAX_DATE_RANGE_DAYS + " days");
        }
        Map<String, Challenge> distinct = new LinkedHashMap<>();
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            findByStartDate(date).forEach(challenge -> distinct.putIfAbsent(challenge.getId(), challenge));
        }
        return recent(distinct.values());
    }

    /** The legacy name is retained; DynamoDB implements normalized title-prefix search only. */
    public List<Challenge> searchChallengesByTitleOrDescription(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return List.of();
        }
        String normalized = ChallengeRepositoryKeys.normalize(searchTerm);
        Map<String, Challenge> distinct = new LinkedHashMap<>();
        for (int shard = 0; shard < DynamoAccessPatterns.SEARCH_SHARDS; shard++) {
            store.queryAll(
                    DynamoAccessPatterns.searchPk("CHALLENGE", shard),
                    ChallengeRepositoryKeys.challengeTitleSearchPrefix(normalized),
                    MAX_BOUNDED_COLLECTION_ITEMS,
                    false,
                    Challenge.class,
                    true
            ).forEach(challenge -> distinct.putIfAbsent(challenge.getId(), challenge));
        }
        return recent(distinct.values());
    }

    /** Exact normalized hashtag lookup; substring matching is intentionally not supported. */
    public List<Challenge> findByHashtagsContaining(String hashtag) {
        if (hashtag == null || hashtag.isBlank()) {
            return List.of();
        }
        return recent(queryProjectionPartition(
                DynamoAccessPatterns.exactTagPk("HASHTAG", ChallengeRepositoryKeys.normalizeHashtag(hashtag)),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    /** Exact normalized category lookup; substring matching is intentionally not supported. */
    public List<Challenge> findByCategoriesContaining(String category) {
        if (category == null || category.isBlank()) {
            return List.of();
        }
        return recent(queryProjectionPartition(
                DynamoAccessPatterns.exactTagPk("CATEGORY", ChallengeRepositoryKeys.normalize(category)),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    /**
     * Bounded newest-first merge across exact hashtag and category partitions.
     * Each returned candidate carries the precise per-partition continuation
     * immediately after that candidate, allowing the service to stop after an
     * authorization/filter match without skipping later rows from the batch.
     */
    public ShardedChallengePage findByTagsPage(
            Collection<String> requestedValues,
            int maximumCandidates,
            Map<String, Map<String, AttributeValue>> suppliedState
    ) {
        int boundedLimit = Math.max(1, Math.min(maximumCandidates, 100));
        int shardPrefetch = Math.min(TAG_SHARD_PREFETCH, boundedLimit);
        List<TagShard> shards = tagShards(requestedValues);
        if (shards.isEmpty()) {
            return ShardedChallengePage.empty();
        }
        if (shards.size() > 40) {
            throw new IllegalArgumentException("At most 20 hashtag/category values may be queried");
        }

        Set<String> expectedShardNames = shards.stream()
                .map(TagShard::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (suppliedState != null
                && !suppliedState.isEmpty()
                && !expectedShardNames.equals(suppliedState.keySet())) {
            throw new IllegalArgumentException("Challenge tag cursor shard set is invalid");
        }

        Map<String, Map<String, AttributeValue>> progress = new LinkedHashMap<>();
        List<TagBuffer> buffers = new ArrayList<>(shards.size());
        PriorityQueue<TagBuffer> heads = new PriorityQueue<>((left, right) -> {
            int challengeOrder = RECENT_FIRST.compare(left.head(), right.head());
            return challengeOrder != 0
                    ? challengeOrder
                    : left.shard().name().compareTo(right.shard().name());
        });
        int fetchedRows = 0;
        int queryCalls = 0;
        for (TagShard shard : shards) {
            Map<String, AttributeValue> shardState = suppliedState == null
                    ? Map.of()
                    : suppliedState.getOrDefault(shard.name(), Map.of());
            if (done(shardState)) {
                progress.put(shard.name(), doneState());
                buffers.add(TagBuffer.done(shard));
                continue;
            }
            validateContinuationKey(shard, shardState);
            int fetchLimit = Math.min(
                    shardPrefetch,
                    MAX_TAG_PROJECTION_CANDIDATES - fetchedRows
            );
            if (fetchLimit < 1) {
                throw new IllegalStateException("Challenge tag initial prefetch budget was exhausted");
            }
            DynamoPage<Challenge> page = queryProjectionPage(
                    shard.partitionKey(),
                    ChallengeRepositoryKeys.CREATED_PREFIX,
                    fetchLimit,
                    shardState,
                    false
            );
            queryCalls++;
            fetchedRows += page.items().size();
            TagBuffer buffer = new TagBuffer(
                    shard,
                    page.items(),
                    page.lastEvaluatedKey(),
                    page.lastEvaluatedKey().isEmpty()
            );
            buffers.add(buffer);
            progress.put(
                    shard.name(),
                    buffer.empty() && buffer.exhausted()
                            ? doneState()
                            : shardState
            );
            if (!buffer.empty()) {
                heads.add(buffer);
            }
        }

        List<ShardedChallengeCandidate> candidates = new ArrayList<>(boundedLimit);
        while (candidates.size() < boundedLimit) {
            boolean refillBlocked = false;
            for (TagBuffer buffer : buffers) {
                if (!buffer.empty() || buffer.exhausted()) {
                    continue;
                }
                int remainingFetchBudget = MAX_TAG_PROJECTION_CANDIDATES - fetchedRows;
                if (remainingFetchBudget < 1 || queryCalls >= 100) {
                    refillBlocked = true;
                    break;
                }
                DynamoPage<Challenge> page = queryProjectionPage(
                        buffer.shard().partitionKey(),
                        ChallengeRepositoryKeys.CREATED_PREFIX,
                        Math.min(shardPrefetch, remainingFetchBudget),
                        buffer.fetchContinuation(),
                        false
                );
                queryCalls++;
                fetchedRows += page.items().size();
                buffer.refill(page.items(), page.lastEvaluatedKey());
                if (!buffer.empty()) {
                    heads.add(buffer);
                } else if (!buffer.exhausted()) {
                    // A filter-free projection query should not produce this,
                    // but stopping preserves order if DynamoDB ever returns an
                    // evaluated empty page with a continuation key.
                    refillBlocked = true;
                    break;
                }
            }
            if (refillBlocked || heads.isEmpty()) {
                break;
            }

            TagBuffer selected = heads.remove();
            Challenge candidate = selected.head();
            List<TagBuffer> matchingHeads = new ArrayList<>();
            matchingHeads.add(selected);
            while (!heads.isEmpty()
                    && java.util.Objects.equals(candidate.getId(), heads.peek().head().getId())) {
                matchingHeads.add(heads.remove());
            }
            for (TagBuffer buffer : matchingHeads) {
                Challenge consumed = buffer.advance();
                progress.put(
                        buffer.shard().name(),
                        buffer.empty() && buffer.exhausted()
                                ? doneState()
                                : projectionKey(buffer.shard(), consumed)
                );
                if (!buffer.empty()) {
                    heads.add(buffer);
                }
            }

            Map<String, Map<String, AttributeValue>> continuation = immutableShardState(progress);
            candidates.add(new ShardedChallengeCandidate(
                    candidate,
                    continuation,
                    hasRemainingShard(buffers, continuation)
            ));
        }

        Map<String, Map<String, AttributeValue>> continuation = immutableShardState(progress);
        return new ShardedChallengePage(
                candidates,
                continuation,
                hasRemainingShard(buffers, continuation)
        );
    }

    public List<Challenge> findByNumberOfDays(Integer numberOfDays) {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.challengeDurationPk(numberOfDays),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    /** Exact, strongly consistent lookup backed by the fixed-key cohort uniqueness item. */
    public Optional<Challenge> findByCohortKey(String cohortKey) {
        if (cohortKey == null || cohortKey.isBlank()) {
            return Optional.empty();
        }
        return findUnique(ChallengeRepositoryKeys.challengeCohortUniquePk(cohortKey));
    }

    /** Applies a field-level update after acquiring the aggregate's distributed lease. */
    public Challenge mutateExisting(String challengeId, Consumer<Challenge> mutation) {
        ChallengeRepositoryKeys.require(challengeId, "challengeId");
        java.util.Objects.requireNonNull(mutation, "mutation");
        leases.acquire(entityType(), challengeId);
        try {
            Challenge latest = findById(challengeId).orElseThrow(() ->
                    new IllegalArgumentException("CHALLENGE " + challengeId + " does not exist"));
            mutation.accept(latest);
            return save(latest);
        } finally {
            leases.release(entityType(), challengeId);
        }
    }

    public void updateChallengeActiveStatus(String id, Boolean active) {
        Challenge challenge = getReferenceById(id);
        challenge.setActive(active);
        save(challenge);
    }

    public List<Challenge> findByCreatorIdInOrCurrentOwnerIdIn(
            List<String> creatorIds,
            List<String> ownerIds
    ) {
        Map<String, Challenge> distinct = new LinkedHashMap<>();
        if (creatorIds != null) {
            new LinkedHashSet<>(creatorIds).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .flatMap(value -> findByCreatorId(value).stream())
                    .forEach(challenge -> distinct.putIfAbsent(challenge.getId(), challenge));
        }
        if (ownerIds != null) {
            new LinkedHashSet<>(ownerIds).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .flatMap(value -> findByCurrentOwnerId(value).stream())
                    .forEach(challenge -> distinct.putIfAbsent(challenge.getId(), challenge));
        }
        return recent(distinct.values());
    }

    @Override
    protected void prepareForSave(Challenge challenge, boolean creating) {
        if (challenge.getCreatedAt() == null) {
            challenge.setCreatedAt(Timestamp.from(clock.instant()));
        }
        if (challenge.getActive() == null) {
            challenge.setActive(true);
        }
        if (challenge.getIsPrivate() == null) {
            challenge.setIsPrivate(false);
        }
    }

    @Override
    protected String id(Challenge challenge) {
        return challenge.getId();
    }

    @Override
    protected void assignId(Challenge challenge, String id) {
        challenge.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(Challenge challenge) {
        String id = ChallengeRepositoryKeys.require(challenge.getId(), "challenge.id");
        String createdSk = ChallengeRepositoryKeys.createdSk(challenge.getCreatedAt(), id);
        List<DynamoProjection> projections = new ArrayList<>();

        add(projections, challenge.getCreatorId(), value -> DynamoProjection.of(
                ChallengeRepositoryKeys.challengeCreatorPk(value), createdSk
        ));
        if (Boolean.TRUE.equals(challenge.getActive()) && !Boolean.TRUE.equals(challenge.getIsPrivate())) {
            add(projections, challenge.getCreatorId(), value -> DynamoProjection.of(
                    ChallengeRepositoryKeys.join("CHALLENGE", "CREATOR", value, "PUBLIC_ACTIVE"), createdSk
            ));
        }
        add(projections, challenge.getCurrentOwnerId(), value -> DynamoProjection.of(
                ChallengeRepositoryKeys.challengeOwnerPk(value), createdSk
        ));

        if (Boolean.TRUE.equals(challenge.getActive())) {
            projections.add(DynamoProjection.of(ChallengeRepositoryKeys.challengeStatePk("ACTIVE"), createdSk));
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.challengeStatePk(
                            Boolean.TRUE.equals(challenge.getIsPrivate()) ? "PRIVATE_ACTIVE" : "PUBLIC_ACTIVE"
                    ),
                    createdSk
            ));
        }
        if (challenge.getStartDate() != null) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.challengeStartDatePk(challenge.getStartDate()), createdSk
            ));
        }
        if (challenge.getNumberOfDays() != null && challenge.getNumberOfDays() > 0) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.challengeDurationPk(challenge.getNumberOfDays()), createdSk
            ));
        }
        if (challenge.getTitle() != null && !challenge.getTitle().isBlank()) {
            String normalizedTitle = ChallengeRepositoryKeys.normalize(challenge.getTitle());
            int shard = DynamoAccessPatterns.stableShard(id, DynamoAccessPatterns.SEARCH_SHARDS);
            projections.add(DynamoProjection.of(
                    DynamoAccessPatterns.searchPk("CHALLENGE", shard),
                    ChallengeRepositoryKeys.challengeTitleSearchSk(
                            normalizedTitle, challenge.getCreatedAt(), id
                    )
            ));
        }
        tokens(challenge.getHashtags(), true, MAX_INDEXED_HASHTAGS).forEach(value -> projections.add(DynamoProjection.of(
                DynamoAccessPatterns.exactTagPk("HASHTAG", value), createdSk
        )));
        tokens(challenge.getCategories(), false, MAX_INDEXED_CATEGORIES).forEach(value -> projections.add(DynamoProjection.of(
                DynamoAccessPatterns.exactTagPk("CATEGORY", value), createdSk
        )));
        return projections;
    }

    @Override
    protected Collection<DynamoProjection> uniqueProjections(Challenge challenge) {
        if (challenge.getCohortKey() == null || challenge.getCohortKey().isBlank()) {
            return List.of();
        }
        return List.of(DynamoProjection.of(
                ChallengeRepositoryKeys.challengeCohortUniquePk(challenge.getCohortKey()),
                ChallengeRepositoryKeys.uniqueSk()
        ));
    }

    private List<Challenge> recentPublicWindow(int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        if (offset < 0 || (long) limit + offset > MAX_BOUNDED_COLLECTION_ITEMS) {
            throw new IllegalArgumentException("requested challenge window is outside the bounded collection");
        }
        List<Challenge> candidates = store.queryAll(
                ChallengeRepositoryKeys.challengeStatePk("PUBLIC_ACTIVE"),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                limit + offset,
                false,
                Challenge.class,
                true
        );
        if (offset >= candidates.size()) {
            return List.of();
        }
        return List.copyOf(candidates.subList(offset, Math.min(candidates.size(), offset + limit)));
    }

    private static List<Challenge> recent(Collection<Challenge> challenges) {
        return challenges.stream().sorted(RECENT_FIRST).toList();
    }

    private static List<String> tokens(String csv, boolean hashtag, int maximumIndexedValues) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            if (!token.isBlank()) {
                normalized.add(hashtag
                        ? ChallengeRepositoryKeys.normalizeHashtag(token)
                        : ChallengeRepositoryKeys.normalize(token));
                if (normalized.size() == maximumIndexedValues) {
                    break;
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static List<TagShard> tagShards(Collection<String> requestedValues) {
        if (requestedValues == null || requestedValues.isEmpty()) {
            return List.of();
        }
        Map<String, TagShard> distinct = new LinkedHashMap<>();
        requestedValues.stream()
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> {
                    String hashtag = ChallengeRepositoryKeys.normalizeHashtag(value);
                    String category = ChallengeRepositoryKeys.normalize(value);
                    distinct.putIfAbsent(
                            "h:" + hashtag,
                            new TagShard(
                                    "h:" + hashtag,
                                    DynamoAccessPatterns.exactTagPk("HASHTAG", hashtag)
                            )
                    );
                    distinct.putIfAbsent(
                            "c:" + category,
                            new TagShard(
                                    "c:" + category,
                                    DynamoAccessPatterns.exactTagPk("CATEGORY", category)
                            )
                    );
                });
        return distinct.values().stream()
                .sorted(Comparator.comparing(TagShard::name))
                .toList();
    }

    private static Map<String, AttributeValue> projectionKey(TagShard shard, Challenge challenge) {
        return Map.of(
                DynamoDbKeys.PARTITION_KEY, AttributeValue.fromS(shard.partitionKey()),
                DynamoDbKeys.SORT_KEY, AttributeValue.fromS(
                        ChallengeRepositoryKeys.createdSk(challenge.getCreatedAt(), challenge.getId())
                )
        );
    }

    private static void validateContinuationKey(
            TagShard shard,
            Map<String, AttributeValue> continuation
    ) {
        if (continuation == null || continuation.isEmpty()) {
            return;
        }
        AttributeValue partition = continuation.get(DynamoDbKeys.PARTITION_KEY);
        AttributeValue sort = continuation.get(DynamoDbKeys.SORT_KEY);
        if (partition == null
                || !shard.partitionKey().equals(partition.s())
                || sort == null
                || sort.s() == null
                || !sort.s().startsWith(ChallengeRepositoryKeys.CREATED_PREFIX)) {
            throw new IllegalArgumentException("Challenge tag cursor key is invalid");
        }
    }

    private static boolean hasRemainingShard(
            Collection<TagBuffer> buffers,
            Map<String, Map<String, AttributeValue>> state
    ) {
        return buffers.stream().anyMatch(TagBuffer::hasRemaining)
                || state.values().stream().anyMatch(value -> !done(value));
    }

    private static boolean done(Map<String, AttributeValue> state) {
        AttributeValue marker = state == null ? null : state.get(CURSOR_DONE);
        return marker != null && "1".equals(marker.s());
    }

    private static Map<String, AttributeValue> doneState() {
        return Map.of(CURSOR_DONE, AttributeValue.fromS("1"));
    }

    private static Map<String, Map<String, AttributeValue>> immutableShardState(
            Map<String, Map<String, AttributeValue>> state
    ) {
        Map<String, Map<String, AttributeValue>> copy = new LinkedHashMap<>();
        state.forEach((name, key) -> copy.put(name, key == null ? Map.of() : Map.copyOf(key)));
        return Map.copyOf(copy);
    }

    public record ShardedChallengeCandidate(
            Challenge challenge,
            Map<String, Map<String, AttributeValue>> continuation,
            boolean hasMore
    ) {
    }

    public record ShardedChallengePage(
            List<ShardedChallengeCandidate> candidates,
            Map<String, Map<String, AttributeValue>> continuation,
            boolean hasMore
    ) {
        private static ShardedChallengePage empty() {
            return new ShardedChallengePage(List.of(), Map.of(), false);
        }
    }

    private record TagShard(String name, String partitionKey) {
    }

    private static final class TagBuffer {
        private final TagShard shard;
        private final ArrayDeque<Challenge> items = new ArrayDeque<>();
        private Map<String, AttributeValue> fetchContinuation;
        private boolean exhausted;

        private TagBuffer(
                TagShard shard,
                List<Challenge> items,
                Map<String, AttributeValue> fetchContinuation,
                boolean exhausted
        ) {
            this.shard = shard;
            if (items != null) {
                this.items.addAll(items);
            }
            this.fetchContinuation = fetchContinuation == null
                    ? Map.of()
                    : Map.copyOf(fetchContinuation);
            this.exhausted = exhausted;
        }

        private static TagBuffer done(TagShard shard) {
            return new TagBuffer(shard, List.of(), Map.of(), true);
        }

        private TagShard shard() {
            return shard;
        }

        private Challenge head() {
            return items.getFirst();
        }

        private Challenge advance() {
            return items.removeFirst();
        }

        private boolean exhausted() {
            return exhausted;
        }

        private boolean empty() {
            return items.isEmpty();
        }

        private boolean hasRemaining() {
            return !items.isEmpty() || !exhausted;
        }

        private Map<String, AttributeValue> fetchContinuation() {
            return fetchContinuation;
        }

        private void refill(
                List<Challenge> fetched,
                Map<String, AttributeValue> nextFetchContinuation
        ) {
            if (!items.isEmpty()) {
                throw new IllegalStateException("Challenge tag buffer must be empty before refill");
            }
            if (fetched != null) {
                items.addAll(fetched);
            }
            fetchContinuation = nextFetchContinuation == null
                    ? Map.of()
                    : Map.copyOf(nextFetchContinuation);
            exhausted = fetchContinuation.isEmpty();
        }
    }

    private static void add(
            Collection<DynamoProjection> projections,
            String value,
            java.util.function.Function<String, DynamoProjection> factory
    ) {
        if (value != null && !value.isBlank()) {
            projections.add(factory.apply(value));
        }
    }
}
