package com.ironside.challengesphere.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/** Durable, per-viewer following timeline stored in the main DynamoDB table. */
@Repository
public class FeedTimelineRepository {

    public static final String PENDING_SUBMISSION_PK = "FEED_TIMELINE#PENDING#SUBMISSION";
    public static final String PENDING_FOLLOW_PK = "FEED_TIMELINE#PENDING#FOLLOW";
    public static final String PENDING_ITEM_PREFIX = "ITEM#";

    private static final String ENTRY_PREFIX = "ENTRY#";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_WRITE_SIZE = 25;
    private static final int MAX_ITEM_BYTES = 350 * 1024;

    private final MainTableGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FeedTimelineRepository(
            MainTableGateway gateway,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Idempotently adds one canonical submission reference to each viewer timeline. */
    public void putForViewers(Collection<String> viewerIds, Submission submission) {
        Objects.requireNonNull(submission, "submission");
        List<DynamoDbItem> entries = new LinkedHashSet<>(viewerIds == null ? List.of() : viewerIds)
                .stream()
                .filter(FeedTimelineRepository::hasText)
                .map(viewerId -> timelineItem(viewerId, submission))
                .toList();
        writeEntries(entries);
    }

    /** Batch-optimized historical backfill for one new follower. */
    public void putForViewer(String viewerId, Collection<Submission> submissions) {
        if (!hasText(viewerId) || submissions == null || submissions.isEmpty()) {
            return;
        }
        writeEntries(submissions.stream()
                .filter(Objects::nonNull)
                .map(submission -> timelineItem(viewerId, submission))
                .toList());
    }

    private void writeEntries(List<DynamoDbItem> entries) {
        for (int offset = 0; offset < entries.size(); offset += MAX_BATCH_WRITE_SIZE) {
            List<DynamoDbItem> batch = entries.subList(
                    offset,
                    Math.min(entries.size(), offset + MAX_BATCH_WRITE_SIZE)
            );
            boolean completed = false;
            for (int attempt = 1; attempt <= 5; attempt++) {
                var result = gateway.batchWrite(batch, List.of());
                if (result.unprocessedWrites().isEmpty()) {
                    completed = true;
                    break;
                }
                if (attempt < 5) {
                    LockSupport.parkNanos((25L << (attempt - 1)) * 1_000_000L);
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Interrupted while retrying durable feed timeline writes"
                        );
                    }
                }
            }
            if (!completed) {
                throw new IllegalStateException(
                        "DynamoDB left durable feed timeline writes unprocessed after 5 attempts"
                );
            }
        }
    }

    private DynamoDbItem timelineItem(String viewerId, Submission submission) {
        String submissionId = requireText(submission.getId(), "submission.id");
        String authorUserId = requireText(submission.getUserId(), "submission.userId");
        Instant createdAt = submission.getCreatedAt() == null
                ? submission.getSubmissionTime() == null
                        ? Instant.EPOCH
                        : submission.getSubmissionTime().toInstant()
                : submission.getCreatedAt().toInstant();
        return DynamoDbItem.create(
                new DynamoDbKey(
                        timelinePartition(viewerId),
                        timelineSortKey(createdAt, submissionId)
                ),
                "FEED_TIMELINE_ENTRY",
                encode(new TimelineEntry(submissionId, authorUserId)),
                clock.instant()
        );
    }

    public TimelinePage findPage(
            String viewerUserId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        String partition = timelinePartition(requireText(viewerUserId, "viewerUserId"));
        int boundedLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(gateway.tableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of(
                        "#pk", DynamoDbKeys.PARTITION_KEY,
                        "#sk", DynamoDbKeys.SORT_KEY
                ))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(partition),
                        ":prefix", AttributeValue.fromS(ENTRY_PREFIX)
                ))
                .scanIndexForward(true)
                .limit(boundedLimit);
        if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
            request.exclusiveStartKey(exclusiveStartKey);
        }
        var page = gateway.query(request.build(), true);
        List<TimelineEntry> entries = page.items().stream()
                .map(this::decodeEntry)
                .toList();
        return new TimelinePage(entries, page.lastEvaluatedKey());
    }

    public static String pendingSortKey(Instant createdAt, String id) {
        long millis = createdAt == null ? 0L : Math.max(0L, createdAt.toEpochMilli());
        return PENDING_ITEM_PREFIX + String.format(Locale.ROOT, "%019d#%s", millis, requireText(id, "id"));
    }

    public static Map<String, AttributeValue> continuationKey(String partitionKey, String sortKey) {
        if (!hasText(sortKey)) {
            return Map.of();
        }
        return Map.of(
                DynamoDbKeys.PARTITION_KEY, AttributeValue.fromS(requireText(partitionKey, "partitionKey")),
                DynamoDbKeys.SORT_KEY, AttributeValue.fromS(sortKey)
        );
    }

    public static String continuationSortKey(Map<String, AttributeValue> continuation) {
        if (continuation == null || continuation.isEmpty()) {
            return null;
        }
        AttributeValue value = continuation.get(DynamoDbKeys.SORT_KEY);
        return value == null ? null : value.s();
    }

    private TimelineEntry decodeEntry(DynamoDbItem item) {
        try {
            TimelineEntry entry = objectMapper.readValue(item.getPayloadJson(), TimelineEntry.class);
            requireText(entry.submissionId(), "timeline.submissionId");
            requireText(entry.authorUserId(), "timeline.authorUserId");
            return entry;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Invalid durable feed timeline entry at " + item.getPk() + '/' + item.getSk(),
                    invalid
            );
        }
    }

    private String encode(TimelineEntry entry) {
        try {
            String json = objectMapper.writeValueAsString(entry);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ITEM_BYTES) {
                throw new IllegalArgumentException("Feed timeline entry exceeds the item-size guardrail");
            }
            return json;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to encode feed timeline entry", failure);
        }
    }

    private static String timelinePartition(String viewerUserId) {
        return "FEED#USER#" + requireText(viewerUserId, "viewerUserId") + "#FOLLOWING";
    }

    private static String timelineSortKey(Instant createdAt, String submissionId) {
        long timestamp = Math.max(0L, createdAt.toEpochMilli());
        return ENTRY_PREFIX + String.format(
                Locale.ROOT,
                "%019d#%s",
                Long.MAX_VALUE - timestamp,
                submissionId
        );
    }

    private static String requireText(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record TimelineEntry(String submissionId, String authorUserId) { }

    public record TimelinePage(
            List<TimelineEntry> entries,
            Map<String, AttributeValue> lastEvaluatedKey
    ) {
        public TimelinePage {
            entries = entries == null ? List.of() : List.copyOf(entries);
            lastEvaluatedKey = lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()
                    ? Map.of()
                    : Map.copyOf(lastEvaluatedKey);
        }

        public boolean hasMore() {
            return !lastEvaluatedKey.isEmpty();
        }
    }
}
