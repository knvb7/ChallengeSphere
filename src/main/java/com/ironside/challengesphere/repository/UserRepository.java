package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter.AggregateWrite;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDocument;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
import com.ironside.challengesphere.util.UserRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

@Repository
public class UserRepository {

    private static final String ENTITY_TYPE = "USER";
    private static final String FOLLOW_ENTITY_TYPE = "FOLLOW";
    private static final int MAX_SEARCH_RESULTS_PER_SHARD = 1_000;
    private static final int MAX_FOLLOW_TRANSITION_ATTEMPTS = 5;
    private static final int MAX_DISCOVERY_RAW_CANDIDATES = 1_000;
    private static final int MAX_INDEXED_INTERESTS = 20;
    private static final String CURSOR_DONE = "_done";

    private final DynamoDomainStore store;
    private final TransactionalDynamoWriter writer;
    private final FollowRepository followRepository;
    private final UserBlockRepository blockRepository;

    public UserRepository(
            DynamoDomainStore store,
            TransactionalDynamoWriter writer,
            FollowRepository followRepository,
            UserBlockRepository blockRepository
    ) {
        this.store = store;
        this.writer = writer;
        this.followRepository = followRepository;
        this.blockRepository = blockRepository;
    }

    public User save(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User must not be null");
        }
        boolean creating = user.getId() == null || user.getId().isBlank();
        if (creating) {
            user.setId(store.nextId());
        }
        requireText(user.getUsername(), "username");
        requireText(user.getEmailId(), "emailId");
        applyDefaults(user);

        Optional<TransactionalDynamoWriter.Versioned<UserDocument>> previous =
                writer.loadVersioned(ENTITY_TYPE, user.getId(), UserDocument.class);
        UserDocument document = UserDocument.from(user);
        writer.save(
                ENTITY_TYPE,
                user.getId(),
                document,
                projections(document),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::projections).orElseGet(List::of),
                uniqueProjections(document),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::uniqueProjections).orElseGet(List::of),
                creating,
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return user;
    }

    public List<User> saveAll(Iterable<User> users) {
        List<User> saved = new ArrayList<>();
        users.forEach(user -> saved.add(save(user)));
        return List.copyOf(saved);
    }

    public Optional<User> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return store.get(ENTITY_TYPE, id, UserDocument.class, true).map(UserDocument::toModel);
    }

    public Optional<User> findByIdForUpdate(String userId) {
        // DynamoDB has no row-level lock. Return a strongly consistent read;
        // repository-owned read/modify/write counters use explicit versions.
        return findById(userId);
    }

    public User getReferenceById(String id) {
        return findById(id).orElseThrow(() -> new IllegalArgumentException("USER " + id + " does not exist"));
    }

    public List<User> findAllById(Iterable<String> ids) {
        List<String> requested = new ArrayList<>();
        ids.forEach(id -> {
            if (id != null && !id.isBlank()) {
                requested.add(id);
            }
        });
        return store.batchGet(ENTITY_TYPE, requested, UserDocument.class, true).stream()
                .map(UserDocument::toModel)
                .toList();
    }

    public List<User> findAll() {
        return queryUsers(
                UserDynamoKeys.collection(ENTITY_TYPE, "ALL"),
                UserDynamoKeys.ITEM_PREFIX,
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true
        );
    }

    public boolean existsById(String id) {
        return findById(id).isPresent();
    }

    public long count() {
        return findAll().size();
    }

    public void delete(User user) {
        if (user == null || user.getId() == null || user.getId().isBlank()) {
            return;
        }
        writer.loadVersioned(ENTITY_TYPE, user.getId(), UserDocument.class)
                .ifPresent(current -> writer.delete(
                        ENTITY_TYPE,
                        user.getId(),
                        projections(current.value()),
                        uniqueProjections(current.value()),
                        current.version()
                ));
    }

    public void deleteById(String id) {
        findById(id).ifPresent(this::delete);
    }

    public void deleteAllById(Iterable<? extends String> ids) {
        ids.forEach(this::deleteById);
    }

    public void deleteAll(Iterable<? extends User> users) {
        users.forEach(this::delete);
    }

    public void flush() {
        // DynamoDB writes are synchronous.
    }

    public User saveAndFlush(User user) {
        return save(user);
    }

    public Optional<User> findByUsername(String username) {
        return uniqueLookup("USERNAME", username, false);
    }

    public boolean existsByUsernameIgnoreCaseAndIdNot(String username, String userId) {
        return findByUsername(username)
                .map(User::getId)
                .filter(existingId -> !existingId.equals(userId))
                .isPresent();
    }

    public Optional<User> findByEmailId(String emailId) {
        return uniqueLookup("EMAIL", emailId, false);
    }

    public Optional<User> findByEmailIdIgnoreCase(String emailId) {
        return findByEmailId(emailId).filter(user -> Boolean.TRUE.equals(user.getActive()));
    }

    public Optional<User> findByPhone(String phone) {
        return uniqueLookup("PHONE", phone, false);
    }

    public Optional<User> findByGoogleId(String googleId) {
        return uniqueLookup("GOOGLE_ID", googleId, true);
    }

    /** Resolves a pre-Dynamo numeric identifier retained solely for cutover compatibility. */
    public Optional<User> findByLegacyId(String legacyId) {
        return uniqueLookup("LEGACY_ID", legacyId, true);
    }

    public boolean existsByUsernameOrEmailIdOrPhone(String username, String emailId, String phone) {
        return findByUsername(username).isPresent()
                || findByEmailId(emailId).isPresent()
                || findByPhone(phone).isPresent();
    }

    public List<User> findAllActiveUsers() {
        return queryUsers(
                UserDynamoKeys.collection(ENTITY_TYPE, "ACTIVE"),
                "RANK#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true
        );
    }

    public List<User> findAllPremiumUsers() {
        return queryUsers(
                UserDynamoKeys.collection(ENTITY_TYPE, "PREMIUM"),
                "RANK#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true
        );
    }

    /** Prefix username search plus exact normalized email lookup. */
    public List<User> searchUsersByUsernameOrEmail(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, User> users = prefixSearch(searchTerm);
        findByEmailId(searchTerm).ifPresent(user -> users.putIfAbsent(user.getId(), user));
        return List.copyOf(users.values());
    }

    public ShardedUserPage searchDiscoverUsersPage(
            String searchTerm,
            String currentUserId,
            int limit,
            Map<String, Map<String, AttributeValue>> shardState
    ) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return ShardedUserPage.empty();
        }
        String normalized = UserDynamoKeys.normalize(searchTerm);
        List<DiscoveryShard> shards = new ArrayList<>(DynamoAccessPatterns.SEARCH_SHARDS + 1);
        for (int shard = 0; shard < DynamoAccessPatterns.SEARCH_SHARDS; shard++) {
            shards.add(new DiscoveryShard(
                    String.format(Locale.ROOT, "s%02d", shard),
                    DynamoAccessPatterns.searchPk(ENTITY_TYPE, shard),
                    normalized
            ));
        }
        shards.add(new DiscoveryShard(
                "email",
                UserDynamoKeys.uniquePk(ENTITY_TYPE, "EMAIL", normalized),
                UserDynamoKeys.LOOKUP
        ));
        Comparator<User> ranking = Comparator
                .comparing((User user) -> safeLower(user.getUsername()))
                .thenComparing(User::getId);
        return mergeDiscoveryShards(
                shards,
                currentUserId,
                limit,
                shardState,
                false,
                ranking
        );
    }

    /**
     * Bounded multiway merge of the viewer's interest partitions. Each
     * partition is already ordered by followers, recent activity, and username.
     */
    public ShardedUserPage findSuggestedUsersPage(
            String currentUserId,
            List<String> requestedInterests,
            int limit,
            Map<String, Map<String, AttributeValue>> shardState
    ) {
        List<String> interests = requestedInterests == null
                ? List.of()
                : requestedInterests.stream()
                        .map(UserDynamoKeys::normalizeNullable)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .sorted()
                        .limit(MAX_INDEXED_INTERESTS)
                        .toList();
        if (interests.isEmpty()) {
            return ShardedUserPage.empty();
        }
        List<DiscoveryShard> shards = new ArrayList<>(interests.size());
        for (int index = 0; index < interests.size(); index++) {
            shards.add(new DiscoveryShard(
                    String.format(Locale.ROOT, "i%03d", index),
                    interestPartition(interests.get(index)),
                    "RANK#"
            ));
        }
        Comparator<User> ranking = Comparator
                .comparingInt(this::followers).reversed()
                .thenComparing(Comparator.comparingLong(
                        (User user) -> timestampMillis(user.getLastLoginAt())
                ).reversed())
                .thenComparing(user -> safeLower(user.getUsername()))
                .thenComparing(User::getId);
        return mergeDiscoveryShards(
                shards,
                currentUserId,
                limit,
                shardState,
                true,
                ranking
        );
    }

    public DynamoPage<User> findFallbackSuggestedUsersPage(
            String currentUserId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int rawBudget = discoveryRawBudget(boundedLimit);
        int examined = 0;
        Map<String, AttributeValue> continuation = exclusiveStartKey == null
                ? Map.of()
                : Map.copyOf(exclusiveStartKey);
        List<User> visible = new ArrayList<>(boundedLimit);
        while (visible.size() < boundedLimit && examined < rawBudget) {
            int rawLimit = Math.min(
                    boundedLimit - visible.size(),
                    rawBudget - examined
            );
            DynamoPage<UserDocument> page = store.query(
                    UserDynamoKeys.collection(ENTITY_TYPE, "ACTIVE"),
                    "RANK#",
                    rawLimit,
                    continuation,
                    true,
                    UserDocument.class,
                    true
            );
            List<User> candidates = page.items().stream().map(UserDocument::toModel).toList();
            examined += candidates.size();
            continuation = page.lastEvaluatedKey();
            addVisibleCandidates(visible, candidates, currentUserId, true, boundedLimit);
            if (continuation.isEmpty() || candidates.isEmpty()) {
                break;
            }
        }
        return new DynamoPage<>(visible, continuation);
    }

    public void updateUserActiveStatus(String id, Boolean active) {
        mutate(id, user -> user.setActive(active));
    }

    public void updateFollowersCount(String id, Integer increment) {
        mutate(id, user -> user.setFollowers(nonNegative(followers(user) + safeIncrement(increment))));
    }

    public void updateFollowingCount(String id, Integer increment) {
        mutate(id, user -> user.setFollowing(nonNegative(following(user) + safeIncrement(increment))));
    }

    /**
     * Changes one follow edge and both denormalized user counters in a single
     * version-fenced DynamoDB transaction. Repeating the requested state is a
     * no-op and does not apply the counters again.
     */
    public FollowTransition transitionFollow(
            String followerId,
            String followingId,
            boolean active
    ) {
        requireText(followerId, "followerId");
        requireText(followingId, "followingId");
        if (followerId.equals(followingId)) {
            throw new IllegalArgumentException("Users cannot follow themselves");
        }

        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < MAX_FOLLOW_TRANSITION_ATTEMPTS; attempt++) {
            Optional<Follow> pairLookup = followRepository.findByFollowerIdAndFollowingId(
                    followerId,
                    followingId
            );
            Optional<TransactionalDynamoWriter.Versioned<Follow>> storedFollow = pairLookup
                    .flatMap(follow -> writer.loadVersioned(
                            FOLLOW_ENTITY_TYPE,
                            follow.getId(),
                            Follow.class
                    ));
            if (pairLookup.isPresent() && storedFollow.isEmpty()) {
                lastConflict = new DataIntegrityViolationException(
                        "Follow pair projection points to a missing aggregate"
                );
                continue;
            }

            Follow follow;
            boolean creating;
            List<DynamoProjection> previousFollowProjections;
            List<DynamoProjection> previousFollowUniqueProjections;
            Long expectedFollowVersion;
            if (storedFollow.isPresent()) {
                follow = storedFollow.get().value();
                if (!followerId.equals(follow.getFollowerId())
                        || !followingId.equals(follow.getFollowingId())) {
                    throw new DataIntegrityViolationException(
                            "Follow pair projection points to a different relationship"
                    );
                }
                if (Boolean.TRUE.equals(follow.getActive()) == active) {
                    return new FollowTransition(follow, false);
                }
                previousFollowProjections = followRepository.transitionProjections(follow);
                previousFollowUniqueProjections = followRepository.transitionUniqueProjections(follow);
                expectedFollowVersion = storedFollow.get().version();
                creating = false;
            } else {
                if (!active) {
                    return new FollowTransition(null, false);
                }
                follow = new Follow();
                follow.setId(store.nextId());
                follow.setFollowerId(followerId);
                follow.setFollowingId(followingId);
                follow.setCreatedAt(new Timestamp(System.currentTimeMillis()));
                previousFollowProjections = List.of();
                previousFollowUniqueProjections = List.of();
                expectedFollowVersion = null;
                creating = true;
            }
            follow.setActive(active);
            if (active) {
                follow.setFeedTimelineBackfillCursor(null);
                follow.setFeedTimelineBackfillCompletedAt(null);
            }

            Optional<TransactionalDynamoWriter.Versioned<UserDocument>> follower =
                    writer.loadVersioned(ENTITY_TYPE, followerId, UserDocument.class);
            Optional<TransactionalDynamoWriter.Versioned<UserDocument>> following =
                    writer.loadVersioned(ENTITY_TYPE, followingId, UserDocument.class);
            if (follower.isEmpty() || following.isEmpty()) {
                throw new DataIntegrityViolationException(
                        "Both users must exist before changing a follow relationship"
                );
            }

            int delta = active ? 1 : -1;
            AggregateWrite followWrite = followAggregateWrite(
                    follow,
                    previousFollowProjections,
                    previousFollowUniqueProjections,
                    creating,
                    expectedFollowVersion
            );
            AggregateWrite followerWrite = counterAggregateWrite(
                    follower.get(),
                    user -> user.setFollowing(nonNegative(following(user) + delta))
            );
            AggregateWrite followingWrite = counterAggregateWrite(
                    following.get(),
                    user -> user.setFollowers(nonNegative(followers(user) + delta))
            );
            try {
                writer.saveAtomically(List.of(followWrite, followerWrite, followingWrite));
                return new FollowTransition(follow, true);
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        throw lastConflict == null
                ? new DataIntegrityViolationException("Follow transition retry budget exhausted")
                : lastConflict;
    }

    public void updateLastLoginAt(String id) {
        mutate(id, user -> user.setLastLoginAt(Timestamp.from(Instant.now())));
    }

    public int batchUpdateLastLoginAt(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (String id : ids.stream().filter(value -> value != null && !value.isBlank()).distinct().toList()) {
            if (mutate(id, user -> user.setLastLoginAt(Timestamp.from(Instant.now())))) {
                updated++;
            }
        }
        return updated;
    }

    public List<User> findRecentlyActiveUsers(int days) {
        Instant cutoff = Instant.now().minus(Math.max(0, days), ChronoUnit.DAYS);
        return queryUsers(
                        UserDynamoKeys.collection(ENTITY_TYPE, "RECENT"),
                        "LOGIN#",
                        UserDynamoKeys.MAX_QUERY_ITEMS,
                        false
                ).stream()
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                .filter(user -> user.getLastLoginAt() != null
                        && !user.getLastLoginAt().toInstant().isBefore(cutoff))
                .toList();
    }

    public int increaseChallengeCreatedCountForAUser(String userId) {
        return mutate(userId, user -> user.setChallengesCreated(
                nonNegative(nullableInt(user.getChallengesCreated()) + 1)
        )) ? 1 : 0;
    }

    public int adjustChallengeAcceptedCountForAUser(String userId, int delta) {
        return mutate(userId, user -> user.setChallengesAccepted(
                nonNegative(nullableInt(user.getChallengesAccepted()) + delta)
        )) ? 1 : 0;
    }

    private void applyDefaults(User user) {
        Timestamp now = Timestamp.from(Instant.now());
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(now);
        }
        if (user.getGenuinenessScore() == null) {
            user.setGenuinenessScore(100);
        }
        if (user.getActive() == null) {
            user.setActive(true);
        }
        if (user.getChallengesCreated() == null) {
            user.setChallengesCreated(0);
        }
        if (user.getChallengesAccepted() == null) {
            user.setChallengesAccepted(0);
        }
        if (user.getFollowers() == null) {
            user.setFollowers(0);
        }
        if (user.getFollowing() == null) {
            user.setFollowing(0);
        }
        if (user.getPremiumUser() == null) {
            user.setPremiumUser(0);
        }
        if (user.getRole() == null) {
            user.setRole(UserRole.USER);
        }
        if (user.getNotifications() == null) {
            user.setNotifications(1);
        }
    }

    private List<DynamoProjection> projections(UserDocument user) {
        List<DynamoProjection> projections = new ArrayList<>();
        projections.add(UserDynamoKeys.projection(
                UserDynamoKeys.collection(ENTITY_TYPE, "ALL"),
                UserDynamoKeys.ITEM_PREFIX + user.id()
        ));

        int followerCount = nullableInt(user.followers());
        String rank = UserDynamoKeys.rankSk(
                followerCount,
                user.lastLoginAt(),
                user.username(),
                user.id()
        );
        if (Boolean.TRUE.equals(user.active())) {
            projections.add(UserDynamoKeys.projection(
                    UserDynamoKeys.collection(ENTITY_TYPE, "ACTIVE"),
                    rank
            ));
            if (user.lastLoginAt() != null) {
                projections.add(UserDynamoKeys.projection(
                        UserDynamoKeys.collection(ENTITY_TYPE, "RECENT"),
                        UserDynamoKeys.recentSk(user.lastLoginAt(), user.id())
                ));
            }
            if (user.interests() != null) {
                user.interests().stream()
                        .map(UserDynamoKeys::normalizeNullable)
                        .filter(value -> value != null)
                        .distinct()
                        .sorted()
                        .limit(MAX_INDEXED_INTERESTS)
                        .forEach(interest -> projections.add(UserDynamoKeys.projection(
                                interestPartition(interest),
                                rank
                        )));
            }
        }
        if (user.premiumUser() != null && user.premiumUser() > 0) {
            projections.add(UserDynamoKeys.projection(
                    UserDynamoKeys.collection(ENTITY_TYPE, "PREMIUM"),
                    rank
            ));
        }
        String normalizedUsername = UserDynamoKeys.normalizeNullable(user.username());
        if (normalizedUsername != null) {
            int shard = DynamoAccessPatterns.stableShard(
                    user.id(),
                    DynamoAccessPatterns.SEARCH_SHARDS
            );
            projections.add(UserDynamoKeys.projection(
                    DynamoAccessPatterns.searchPk(ENTITY_TYPE, shard),
                    DynamoAccessPatterns.searchSk(normalizedUsername, user.id())
            ));
        }
        return List.copyOf(projections);
    }

    private List<DynamoProjection> uniqueProjections(UserDocument user) {
        List<DynamoProjection> projections = new ArrayList<>(5);
        addNormalizedUnique(projections, "USERNAME", user.username());
        addNormalizedUnique(projections, "EMAIL", user.emailId());
        addNormalizedUnique(projections, "PHONE", user.phone());
        if (user.legacyId() != null && !user.legacyId().isBlank()) {
            projections.add(UserDynamoKeys.projection(
                    UserDynamoKeys.uniquePkExact(
                            ENTITY_TYPE,
                            "LEGACY_ID",
                            user.legacyId().strip()
                    ),
                    UserDynamoKeys.LOOKUP
            ));
        }
        if (user.googleId() != null && !user.googleId().isBlank()) {
            projections.add(UserDynamoKeys.projection(
                    UserDynamoKeys.uniquePkExact(
                            ENTITY_TYPE,
                            "GOOGLE_ID",
                            user.googleId().strip()
                    ),
                    UserDynamoKeys.LOOKUP
            ));
        }
        return List.copyOf(projections);
    }

    private void addNormalizedUnique(
            List<DynamoProjection> destination,
            String field,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            destination.add(UserDynamoKeys.projection(
                    UserDynamoKeys.uniquePk(ENTITY_TYPE, field, value),
                    UserDynamoKeys.LOOKUP
            ));
        }
    }

    private Optional<User> uniqueLookup(String field, String value, boolean exactCase) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String partitionKey = exactCase
                ? UserDynamoKeys.uniquePkExact(ENTITY_TYPE, field, value.strip())
                : UserDynamoKeys.uniquePk(ENTITY_TYPE, field, value);
        return store.queryAll(
                        partitionKey,
                        UserDynamoKeys.LOOKUP,
                        1,
                        true,
                        UserDocument.class,
                        true
                ).stream()
                .findFirst()
                .map(UserDocument::toModel);
    }

    private LinkedHashMap<String, User> prefixSearch(String searchTerm) {
        String normalized = UserDynamoKeys.normalize(searchTerm);
        LinkedHashMap<String, User> matches = new LinkedHashMap<>();
        for (int shard = 0; shard < DynamoAccessPatterns.SEARCH_SHARDS; shard++) {
            store.queryAll(
                            DynamoAccessPatterns.searchPk(ENTITY_TYPE, shard),
                            normalized,
                            MAX_SEARCH_RESULTS_PER_SHARD,
                            true,
                            UserDocument.class,
                            true
                    ).stream()
                    .map(UserDocument::toModel)
                    .forEach(user -> matches.putIfAbsent(user.getId(), user));
        }
        return matches;
    }

    private List<User> queryUsers(
            String partitionKey,
            String sortPrefix,
            int maximum,
            boolean ascending
    ) {
        return store.queryAll(
                        partitionKey,
                        sortPrefix,
                        maximum,
                        ascending,
                        UserDocument.class,
                        true
                ).stream()
                .map(UserDocument::toModel)
                .toList();
    }

    private String interestPartition(String normalizedInterest) {
        return DynamoDbKeys.join(
                "DISCOVER",
                "INTEREST",
                UserDynamoKeys.sha256(normalizedInterest)
        );
    }

    private ShardedUserPage mergeDiscoveryShards(
            List<DiscoveryShard> shards,
            String currentUserId,
            int limit,
            Map<String, Map<String, AttributeValue>> suppliedState,
            boolean excludeFollowed,
            Comparator<User> ranking
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int rawBudget = discoveryRawBudget(boundedLimit);
        Map<String, Map<String, AttributeValue>> state = new LinkedHashMap<>();
        for (DiscoveryShard shard : shards) {
            state.put(shard.name(), suppliedState == null
                    ? Map.of()
                    : suppliedState.getOrDefault(shard.name(), Map.of()));
        }

        List<User> visible = new ArrayList<>(boundedLimit);
        Map<String, ShardHead> heads = new LinkedHashMap<>();
        int examined = 0;
        while (visible.size() < boundedLimit && examined < rawBudget) {
            int rawLimit = Math.min(boundedLimit - visible.size(), rawBudget - examined);
            List<User> raw = new ArrayList<>(rawLimit);
            while (raw.size() < rawLimit) {
                for (DiscoveryShard shard : shards) {
                    Map<String, AttributeValue> cursor = state.getOrDefault(shard.name(), Map.of());
                    if (done(cursor) || heads.containsKey(shard.name())) {
                        continue;
                    }
                    DynamoPage<UserDocument> page = store.query(
                            shard.partitionKey(),
                            shard.sortKeyPrefix(),
                            1,
                            cursor,
                            true,
                            UserDocument.class,
                            true
                    );
                    if (page.items().isEmpty()) {
                        state.put(shard.name(), doneState());
                        continue;
                    }
                    heads.put(shard.name(), new ShardHead(
                            shard.name(),
                            page.items().get(0).toModel(),
                            page.lastEvaluatedKey()
                    ));
                }
                if (heads.isEmpty()) {
                    break;
                }
                ShardHead selected = heads.values().stream()
                        .min(Comparator.comparing(ShardHead::user, ranking)
                                .thenComparing(ShardHead::shardName))
                        .orElseThrow();
                List<ShardHead> consumed = heads.values().stream()
                        .filter(head -> selected.user().getId().equals(head.user().getId()))
                        .toList();
                consumed.forEach(head -> {
                    state.put(
                                head.shardName(),
                                head.nextKey().isEmpty() ? doneState() : head.nextKey()
                    );
                    heads.remove(head.shardName());
                });
                raw.add(selected.user());
                examined++;
            }
            addVisibleCandidates(visible, raw, currentUserId, excludeFollowed, boundedLimit);
            if (raw.isEmpty()) {
                break;
            }
        }

        boolean hasMore = !heads.isEmpty() || shards.stream()
                .anyMatch(shard -> !done(state.getOrDefault(shard.name(), Map.of())));
        return new ShardedUserPage(visible, state, hasMore);
    }

    private void addVisibleCandidates(
            List<User> destination,
            List<User> candidates,
            String currentUserId,
            boolean excludeFollowed,
            int maximum
    ) {
        if (candidates == null || candidates.isEmpty() || destination.size() >= maximum) {
            return;
        }
        List<String> candidateIds = candidates.stream()
                .map(User::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Set<String> blocked = blockRepository.findActiveBlockedCounterpartIds(
                currentUserId,
                candidateIds
        );
        Set<String> followed = excludeFollowed
                ? followRepository.findActiveFollowedCandidateIds(currentUserId, candidateIds)
                : Set.of();
        LinkedHashSet<String> alreadyAdded = destination.stream()
                .map(User::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        candidates.stream()
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                .filter(user -> currentUserId == null || !currentUserId.equals(user.getId()))
                .filter(user -> !blocked.contains(user.getId()))
                .filter(user -> !followed.contains(user.getId()))
                .filter(user -> alreadyAdded.add(user.getId()))
                .limit(maximum - destination.size())
                .forEach(destination::add);
    }

    private static int discoveryRawBudget(int limit) {
        return Math.min(MAX_DISCOVERY_RAW_CANDIDATES, Math.max(limit, limit * 10));
    }

    private static boolean done(Map<String, AttributeValue> state) {
        AttributeValue marker = state == null ? null : state.get(CURSOR_DONE);
        return marker != null && "1".equals(marker.s());
    }

    private static Map<String, AttributeValue> doneState() {
        return Map.of(CURSOR_DONE, AttributeValue.fromS("1"));
    }

    private AggregateWrite followAggregateWrite(
            Follow follow,
            List<DynamoProjection> previousProjections,
            List<DynamoProjection> previousUniqueProjections,
            boolean creating,
            Long expectedVersion
    ) {
        return new AggregateWrite(
                FOLLOW_ENTITY_TYPE,
                follow.getId(),
                follow,
                followRepository.transitionProjections(follow),
                previousProjections,
                followRepository.transitionUniqueProjections(follow),
                previousUniqueProjections,
                creating,
                expectedVersion
        );
    }

    private AggregateWrite counterAggregateWrite(
            TransactionalDynamoWriter.Versioned<UserDocument> versioned,
            Consumer<User> mutation
    ) {
        UserDocument previous = versioned.value();
        User updatedUser = previous.toModel();
        mutation.accept(updatedUser);
        applyDefaults(updatedUser);
        UserDocument updated = UserDocument.from(updatedUser);
        return new AggregateWrite(
                ENTITY_TYPE,
                updated.id(),
                updated,
                projections(updated),
                projections(previous),
                uniqueProjections(updated),
                uniqueProjections(previous),
                false,
                versioned.version()
        );
    }

    private boolean mutate(String id, Consumer<User> mutation) {
        if (id == null || id.isBlank()) {
            return false;
        }
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<TransactionalDynamoWriter.Versioned<UserDocument>> existing =
                    writer.loadVersioned(ENTITY_TYPE, id, UserDocument.class);
            if (existing.isEmpty()) {
                return false;
            }
            TransactionalDynamoWriter.Versioned<UserDocument> versioned = existing.get();
            UserDocument previous = versioned.value();
            User user = previous.toModel();
            mutation.accept(user);
            applyDefaults(user);
            UserDocument updated = UserDocument.from(user);
            try {
                writer.save(
                        ENTITY_TYPE,
                        user.getId(),
                        updated,
                        projections(updated),
                        projections(previous),
                        uniqueProjections(updated),
                        uniqueProjections(previous),
                        false,
                        versioned.version()
                );
                return true;
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        throw lastConflict;
    }

    private int followers(User user) {
        return nullableInt(user.getFollowers());
    }

    private int following(User user) {
        return nullableInt(user.getFollowing());
    }

    private int safeIncrement(Integer increment) {
        return increment == null ? 0 : increment;
    }

    private int nullableInt(Integer value) {
        return value == null ? 0 : value;
    }

    private int nonNegative(int value) {
        return Math.max(0, value);
    }

    private long timestampMillis(Timestamp timestamp) {
        return timestamp == null ? 0L : timestamp.getTime();
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record ShardedUserPage(
            List<User> items,
            Map<String, Map<String, AttributeValue>> shardState,
            boolean hasMore
    ) {
        public ShardedUserPage {
            items = items == null ? List.of() : List.copyOf(items);
            shardState = shardState == null ? Map.of() : Map.copyOf(shardState);
        }

        private static ShardedUserPage empty() {
            return new ShardedUserPage(List.of(), Map.of(), false);
        }
    }

    private record DiscoveryShard(String name, String partitionKey, String sortKeyPrefix) {
    }

    private record ShardHead(
            String shardName,
            User user,
            Map<String, AttributeValue> nextKey
    ) {
    }

    public record FollowTransition(Follow follow, boolean changed) {
    }
}
