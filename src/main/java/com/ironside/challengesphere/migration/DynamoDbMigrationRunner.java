package com.ironside.challengesphere.migration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.model.*;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore;
import com.ironside.challengesphere.persistence.dynamodb.operations.OfficialChallengeCatalog;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeParticipantCountStore;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeRepositoryKeys;
import com.ironside.challengesphere.repository.*;
import com.ironside.challengesphere.service.impl.FeedTimelineMaterializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import java.util.Set;

/**
 * Offline, resumable PostgreSQL-to-DynamoDB importer used by the guarded
 * migration scripts. PLAN is read-only. EXECUTE uses deterministic ULIDs and
 * repository writes so canonical items and every access projection converge on
 * repeated runs with the same source snapshot.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "app.migration", name = "run", havingValue = "true")
public class DynamoDbMigrationRunner implements ApplicationRunner {

    private static final Logger logger = LogManager.getLogger(DynamoDbMigrationRunner.class);
    private static final String SCHEMA = "challengesphere";
    private static final String APPLE_ACCOUNT_EVENTS_TABLE = "apple_account_events";
    private static final String ACCOUNT_DELETION_REQUESTS_TABLE = "account_deletion_requests";
    private static final String EXPECTED_SOURCE_FLYWAY_VERSION = "53";
    /**
     * Bump whenever repository serialization or projection layout changes.
     * Checkpoints from another mapping version must never be resumed because
     * their completed source rows may be missing newly required projections.
     */
    private static final String TARGET_MAPPING_VERSION = "2026-08-22-v3";
    private static final Set<String> EXPECTED_SOURCE_TABLES = Set.of(
            "account_deletion_requests",
            "account_media_cleanup_queue",
            "apple_account_events",
            "auth_identities",
            "auth_tokens",
            "challenge_crew_invites",
            "challenge_crew_lifecycle",
            "challenge_invites",
            "challenge_peer_nudges",
            "challenge_peer_reminder_preferences",
            "challenges",
            "comments",
            "content_reports",
            "feature_requests",
            "follows",
            "glories",
            "invite_usages",
            "leaderboard_entries",
            "moderation_flags",
            "notifications",
            "official_challenge_templates",
            "provider_token_revocations",
            "push_device_tokens",
            "reports",
            "submissions",
            "subscription_webhook_events",
            "user_blocks",
            "user_challenge_milestones",
            "user_challenges",
            "user_preferences",
            "user_reports",
            "user_subscriptions",
            "users"
    );
    private static final int BATCH_SIZE = 100;
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final List<EntitySpec> SPECS = List.of(
            spec("users", "id", "USER", User.class, UserRepository.class),
            spec("auth_identities", "id", "AUTH_IDENTITY", AuthIdentity.class, AuthIdentityRepository.class),
            spec("challenges", "id", "CHALLENGE", Challenge.class, ChallengeRepository.class),
            spec("user_challenges", "id", "USER_CHALLENGE", UserChallenge.class, UserChallengeRepository.class),
            spec("submissions", "id", "SUBMISSION", Submission.class, SubmissionRepository.class),
            spec("follows", "id", "FOLLOW", Follow.class, FollowRepository.class),
            spec("comments", "id", "COMMENT", Comment.class, CommentRepository.class),
            spec("glories", "id", "GLORY", Glory.class, GloryRepository.class),
            spec("notifications", "id", "NOTIFICATION", Notification.class, NotificationRepository.class),
            spec("reports", "id", "REPORT", Report.class, ReportRepository.class),
            spec("content_reports", "id", "REPORT", Report.class, ReportRepository.class),
            spec("leaderboard_entries", "id", "LEADERBOARD_ENTRY", LeaderboardEntry.class, LeaderboardEntryRepository.class),
            spec("challenge_invites", "id", "CHALLENGE_INVITE", ChallengeInvite.class, ChallengeInviteRepository.class),
            spec("invite_usages", "id", "INVITE_USAGE", InviteUsage.class, InviteUsageRepository.class),
            spec("user_preferences", "id", "USER_PREFERENCES", UserPreferences.class, UserPreferencesRepository.class),
            spec("moderation_flags", "id", "MODERATION_FLAG", ModerationFlag.class, ModerationFlagRepository.class),
            spec("user_blocks", "id", "USER_BLOCK", UserBlock.class, UserBlockRepository.class),
            spec("user_reports", "id", "USER_REPORT", UserReport.class, UserReportRepository.class),
            spec("push_device_tokens", "id", "PUSH_DEVICE_TOKEN", PushDeviceToken.class, PushDeviceTokenRepository.class),
            spec("feature_requests", "id", "FEATURE_REQUEST", FeatureRequest.class, FeatureRequestRepository.class),
            spec("challenge_crew_lifecycle", "challenge_id", "CHALLENGE_CREW_LIFECYCLE", ChallengeCrewLifecycle.class, ChallengeCrewLifecycleRepository.class),
            spec("challenge_crew_invites", "id", "CHALLENGE_CREW_INVITE", ChallengeCrewInvite.class, ChallengeCrewInviteRepository.class),
            spec("challenge_peer_reminder_preferences", "id", "CHALLENGE_PEER_REMINDER_PREFERENCE", ChallengePeerReminderPreference.class, ChallengePeerReminderPreferenceRepository.class),
            spec("challenge_peer_nudges", "id", "CHALLENGE_PEER_NUDGE", ChallengePeerNudge.class, ChallengePeerNudgeRepository.class),
            spec("user_challenge_milestones", "id", "USER_CHALLENGE_MILESTONE", UserChallengeMilestone.class, UserChallengeMilestoneRepository.class),
            spec("user_subscriptions", "user_id", "USER_SUBSCRIPTION", UserSubscription.class, UserSubscriptionRepository.class),
            spec("subscription_webhook_events", "event_id", "SUBSCRIPTION_WEBHOOK_EVENT", SubscriptionWebhookEvent.class, SubscriptionWebhookEventRepository.class)
    );

    private static final Map<String, String> FOREIGN_ID_TARGETS = Map.ofEntries(
            Map.entry("user_id", "USER"),
            Map.entry("creator_id", "USER"),
            Map.entry("current_owner_id", "USER"),
            Map.entry("follower_id", "USER"),
            Map.entry("following_id", "USER"),
            Map.entry("blocker_user_id", "USER"),
            Map.entry("blocked_user_id", "USER"),
            Map.entry("reporter_user_id", "USER"),
            Map.entry("reported_user_id", "USER"),
            Map.entry("target_owner_user_id", "USER"),
            Map.entry("reviewed_by", "USER"),
            Map.entry("owner_user_id", "USER"),
            Map.entry("invited_user_id", "USER"),
            Map.entry("invited_by_user_id", "USER"),
            Map.entry("created_by", "USER"),
            Map.entry("recipient_user_id", "USER"),
            Map.entry("sender_user_id", "USER"),
            Map.entry("removed_by_user_id", "USER"),
            Map.entry("started_by_user_id", "USER"),
            Map.entry("challenge_id", "CHALLENGE"),
            Map.entry("rematch_of_challenge_id", "CHALLENGE"),
            Map.entry("submission_id", "SUBMISSION"),
            Map.entry("comment_id", "COMMENT"),
            Map.entry("parent_comment_id", "COMMENT"),
            Map.entry("invite_id", "CHALLENGE_INVITE"),
            Map.entry("notification_id", "NOTIFICATION")
    );

    private static final Map<String, String> EMBEDDED_ID_TARGETS = Map.ofEntries(
            Map.entry("actorUserId", "USER"),
            Map.entry("targetUserId", "USER"),
            Map.entry("creatorUserId", "USER"),
            Map.entry("ownerUserId", "USER"),
            Map.entry("recipientUserId", "USER"),
            Map.entry("senderUserId", "USER"),
            Map.entry("challengeId", "CHALLENGE"),
            Map.entry("submissionId", "SUBMISSION"),
            Map.entry("commentId", "COMMENT"),
            Map.entry("inviteId", "CHALLENGE_INVITE"),
            Map.entry("notificationId", "NOTIFICATION")
    );

    private final ApplicationContext applicationContext;
    private final ObjectMapper mapper;
    private final DynamoDbClient dynamoDbClient;
    private final MainTableGateway mainTableGateway;
    private final DynamoOperationalStore operationalStore;
    private final String mainTable;
    private final com.ironside.challengesphere.config.DynamoDbConfig.DynamoDbProperties dynamoProperties;
    private final AwsCredentialsProvider credentialsProvider;

    public DynamoDbMigrationRunner(
            ApplicationContext applicationContext,
            ObjectMapper objectMapper,
            DynamoDbClient dynamoDbClient,
            MainTableGateway mainTableGateway,
            DynamoOperationalStore operationalStore,
            com.ironside.challengesphere.config.DynamoDbConfig.DynamoDbProperties dynamoProperties,
            @Qualifier("dynamoDbCredentialsProvider") AwsCredentialsProvider credentialsProvider,
            @org.springframework.beans.factory.annotation.Value("${app.dynamodb.main-table}") String mainTable
    ) {
        this.applicationContext = applicationContext;
        this.mapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.dynamoDbClient = dynamoDbClient;
        this.mainTableGateway = mainTableGateway;
        this.operationalStore = operationalStore;
        this.dynamoProperties = dynamoProperties;
        this.credentialsProvider = credentialsProvider;
        this.mainTable = mainTable;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            requireEnabled();
            MigrationMode mode = MigrationMode.valueOf(environment("DYNAMODB_MIGRATION_MODE", "PLAN")
                    .toUpperCase(Locale.ROOT));
            String jdbcUrl = requireEnvironment("DB_URL");
            String username = requireEnvironment("DB_USERNAME");
            String password = System.getenv().getOrDefault("DB_PASSWORD", "");

            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
                configureSourceConnection(connection);
                MigrationPlan plan = buildPlan(connection);
                if (mode == MigrationMode.PLAN) {
                    logger.info(
                            "DynamoDB migration PLAN sourceRows={} tables={} fingerprint={} mappingVersion={}",
                            plan.sourceRows(), plan.tableRows(), plan.schemaFingerprint(), TARGET_MAPPING_VERSION
                    );
                    connection.rollback();
                    return;
                }
                execute(connection, plan);
                connection.rollback();
            }
        } finally {
            if (applicationContext instanceof ConfigurableApplicationContext configurable) {
                configurable.close();
            }
        }
    }

    private void execute(Connection connection, MigrationPlan plan) throws Exception {
        validateExecutionTarget();
        if (!"true".equalsIgnoreCase(environment("SOURCE_WRITES_DISABLED", "false"))) {
            throw new IllegalStateException("SOURCE_WRITES_DISABLED=true is required for EXECUTE");
        }
        if (!"true".equalsIgnoreCase(environment("FORCED_REAUTH_ACCEPTED", "false"))) {
            throw new IllegalStateException("FORCED_REAUTH_ACCEPTED=true is required for EXECUTE");
        }
        String runId = requireEnvironment("DYNAMODB_MIGRATION_RUN_ID");
        boolean resume = validateResumeCheckpoint(runId, plan);
        if (!resume) {
            requireFreshMainTable();
        }
        writeSummary(runId, "RUNNING", plan, 0, 0, 0, 0, 0,
                false, 0, 0, false, 0, 0, null);

        long canonicalItems = 0;
        FeedTimelineMaterializer.DrainResult timelineDrain =
                new FeedTimelineMaterializer.DrainResult(0L, 0L);
        boolean feedTimelineReady = false;
        ParticipantReconciliation participantReconciliation =
                new ParticipantReconciliation(0L, 0L);
        boolean challengeParticipantsReady = false;
        try {
            for (EntitySpec spec : SPECS) {
                long sourceRows = plan.tableRows().getOrDefault(spec.table(), 0L);
                if (sourceRows == 0 && !tableExists(connection, spec.table())) continue;
                TableProgress progress = readTableProgress(
                        runId, spec.table(), sourceRows, plan.schemaFingerprint()
                );
                long processed = progress.processedRows();
                String lastSourceKey = progress.lastSourceKey();
                if (processed > sourceRows) {
                    throw new IllegalStateException("Checkpoint exceeds source count for " + spec.table());
                }
                while (processed < sourceRows) {
                    List<Map<String, Object>> rows = readRows(
                            connection, spec, lastSourceKey, BATCH_SIZE
                    );
                    if (rows.isEmpty()) throw new IllegalStateException("Source changed during migration: " + spec.table());
                    for (Map<String, Object> row : rows) {
                        importRow(spec, row);
                        processed++;
                        canonicalItems++;
                        lastSourceKey = sourceKey(row, spec.primaryColumn());
                    }
                    writeTableProgress(
                            runId,
                            spec.table(),
                            processed,
                            sourceRows,
                            plan.schemaFingerprint(),
                            lastSourceKey
                    );
                }
            }

            canonicalItems += importCompletedAccountDeletionRetentions(connection, runId, plan);
            canonicalItems += importCompletedAppleAccountEvents(connection, runId, plan);

            participantReconciliation = reconcileAndVerifyChallengeParticipants(connection);
            challengeParticipantsReady = true;

            FeedTimelineMaterializer timelineMaterializer = applicationContext.getBean(
                    FeedTimelineMaterializer.class
            );
            timelineDrain = timelineMaterializer.drainPendingForCutover();
            long pendingSubmissionFanouts = countPartition(
                    FeedTimelineRepository.PENDING_SUBMISSION_PK,
                    FeedTimelineRepository.PENDING_ITEM_PREFIX
            );
            long pendingFollowBackfills = countPartition(
                    FeedTimelineRepository.PENDING_FOLLOW_PK,
                    FeedTimelineRepository.PENDING_ITEM_PREFIX
            );
            feedTimelineReady = timelineMaterializer.isCutoverReady()
                    && pendingSubmissionFanouts == 0L
                    && pendingFollowBackfills == 0L;
            if (!feedTimelineReady) {
                throw new IllegalStateException(
                        "Following timeline cutover is not ready: pendingSubmissionFanouts="
                                + pendingSubmissionFanouts
                                + ", pendingFollowBackfills=" + pendingFollowBackfills
                );
            }

            long projectionItems = verifyCollections(plan);
            long verifiedDomainItems = verifyCanonicalItems(connection, plan);
            long verifiedAccountDeletionRetentions = verifyAccountDeletionRetentions(connection, plan);
            long verifiedAppleEvents = verifyCompletedAppleAccountEvents(connection, plan);
            long verifiedCanonicalItems = verifiedDomainItems
                    + verifiedAccountDeletionRetentions
                    + verifiedAppleEvents;
            if (verifiedCanonicalItems != plan.sourceRows()) {
                throw new IllegalStateException("Source verification count mismatch");
            }
            validateFreshSourceSnapshot(connection, plan);
            writeSummary(
                    runId,
                    "VERIFIED",
                    plan,
                    verifiedCanonicalItems,
                    projectionItems,
                    verifiedAppleEvents,
                    verifiedAccountDeletionRetentions,
                    0,
                    feedTimelineReady,
                    timelineDrain.submissionChunks(),
                    timelineDrain.followChunks(),
                    challengeParticipantsReady,
                    participantReconciliation.challengeCounterCount(),
                    participantReconciliation.activeProjectionCount(),
                    Instant.now()
            );
            logger.info(
                    "DynamoDB migration VERIFIED runId={} sourceRows={} canonicalItems={} "
                            + "accountDeletionRetentions={} terminalAppleEvents={} projectionItems={} "
                            + "feedTimelineReady={} submissionFanoutChunks={} followBackfillChunks={} "
                            + "challengeParticipantsReady={} participantCounters={} activeParticipantProjections={}",
                    runId,
                    plan.sourceRows(),
                    verifiedCanonicalItems,
                    verifiedAccountDeletionRetentions,
                    verifiedAppleEvents,
                    projectionItems,
                    feedTimelineReady,
                    timelineDrain.submissionChunks(),
                    timelineDrain.followChunks(),
                    challengeParticipantsReady,
                    participantReconciliation.challengeCounterCount(),
                    participantReconciliation.activeProjectionCount()
            );
        } catch (Exception failure) {
            writeError(runId, failure);
            writeSummary(runId, "FAILED", plan, canonicalItems, 0, 0, 0, 1,
                    feedTimelineReady,
                    timelineDrain.submissionChunks(),
                    timelineDrain.followChunks(),
                    challengeParticipantsReady,
                    participantReconciliation.challengeCounterCount(),
                    participantReconciliation.activeProjectionCount(),
                    Instant.now());
            throw failure;
        }
    }

    /**
     * Revalidates the target with the exact credential provider used by the
     * DynamoDB client. The shell's AWS CLI identity is an independent process
     * and is not sufficient protection against SDK credential-chain drift.
     */
    private void validateExecutionTarget() {
        if (dynamoProperties.getEndpoint() != null) {
            throw new IllegalStateException(
                    "DynamoDB endpoint overrides are forbidden for migration EXECUTE"
            );
        }
        String expectedAccountId = requireEnvironment("EXPECTED_AWS_ACCOUNT_ID");
        if (!expectedAccountId.matches("[0-9]{12}")) {
            throw new IllegalStateException("EXPECTED_AWS_ACCOUNT_ID must be exactly 12 digits");
        }
        requireTargetValue("AWS_REGION", dynamoProperties.getRegion());
        requireTargetValue("DYNAMODB_MAIN_TABLE_NAME", dynamoProperties.getMainTable());
        requireTargetValue("DYNAMODB_EPHEMERAL_TABLE_NAME", dynamoProperties.getEphemeralTable());

        String actualAccountId;
        try (StsClient sts = StsClient.builder()
                .region(Region.of(dynamoProperties.getRegion()))
                .credentialsProvider(credentialsProvider)
                .build()) {
            actualAccountId = sts.getCallerIdentity().account();
        }
        if (!expectedAccountId.equals(actualAccountId)) {
            throw new IllegalStateException(
                    "DynamoDB SDK account mismatch: expected " + expectedAccountId
                            + " but resolved " + actualAccountId
            );
        }
        logger.info(
                "Migration SDK target validated account={} region={} mainTable={} ephemeralTable={}",
                actualAccountId,
                dynamoProperties.getRegion(),
                dynamoProperties.getMainTable(),
                dynamoProperties.getEphemeralTable()
        );
    }

    private static void requireTargetValue(String environmentName, String configuredValue) {
        String expectedValue = requireEnvironment(environmentName);
        if (!expectedValue.equals(configuredValue)) {
            throw new IllegalStateException(
                    environmentName + " does not match the Java SDK target: expected "
                            + expectedValue + " but configured " + configuredValue
            );
        }
    }

    private MigrationPlan buildPlan(Connection connection) throws SQLException {
        validateSourceIdentityAndManifest(connection);
        validateOperationalCutoverState(connection);
        validateOfficialChallengeCatalog(connection);
        Map<String, Long> tableRows = new LinkedHashMap<>();
        long total = 0;
        for (EntitySpec spec : SPECS) {
            if (!tableExists(connection, spec.table())) continue;
            long count = scalarLong(connection, "SELECT COUNT(*) FROM " + quoted(SCHEMA) + '.' + quoted(spec.table()));
            tableRows.put(spec.table(), count);
            total += count;
        }
        if (!tableExists(connection, ACCOUNT_DELETION_REQUESTS_TABLE)) {
            throw new IllegalStateException(
                    "Required source table is missing: " + SCHEMA + '.' + ACCOUNT_DELETION_REQUESTS_TABLE
            );
        }
        long accountDeletionRows = scalarLong(
                connection,
                "SELECT COUNT(*) FROM " + quoted(SCHEMA) + '.'
                        + quoted(ACCOUNT_DELETION_REQUESTS_TABLE)
        );
        tableRows.put(ACCOUNT_DELETION_REQUESTS_TABLE, accountDeletionRows);
        total += accountDeletionRows;
        if (tableExists(connection, APPLE_ACCOUNT_EVENTS_TABLE)) {
            long count = scalarLong(
                    connection,
                    "SELECT COUNT(*) FROM " + quoted(SCHEMA) + '.'
                            + quoted(APPLE_ACCOUNT_EVENTS_TABLE)
            );
            tableRows.put(APPLE_ACCOUNT_EVENTS_TABLE, count);
            total += count;
        }
        Map<String, Long> immutableTableRows = Map.copyOf(tableRows);
        return new MigrationPlan(
                total,
                immutableTableRows,
                sourceFingerprint(connection, immutableTableRows)
        );
    }

    private static void configureSourceConnection(Connection connection) throws SQLException {
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
    }

    /**
     * Ends the import snapshot and recomputes the plan in a new transaction on
     * the already authenticated source connection. This catches commits that
     * became visible while DynamoDB was being populated before VERIFIED is
     * written.
     */
    private void validateFreshSourceSnapshot(
            Connection connection,
            MigrationPlan original
    ) throws SQLException {
        connection.rollback();
        MigrationPlan fresh = buildPlan(connection);
        if (!original.equals(fresh)) {
            throw new IllegalStateException(
                    "PostgreSQL source changed during migration; the fresh source fingerprint "
                            + "does not match the imported snapshot"
            );
        }
        logger.info(
                "Fresh PostgreSQL source snapshot matches imported sourceRows={} fingerprint={}",
                fresh.sourceRows(),
                fresh.schemaFingerprint()
        );
    }

    private void validateSourceIdentityAndManifest(Connection connection) throws SQLException {
        String expectedDatabase = requireEnvironment("EXPECTED_SOURCE_DATABASE_NAME");
        String actualDatabase = scalarString(connection, "SELECT current_database()");
        if (!expectedDatabase.equals(actualDatabase)) {
            throw new IllegalStateException(
                    "PostgreSQL database mismatch: expected " + expectedDatabase
                            + " but connected to " + actualDatabase
            );
        }

        Set<String> actualTables = new LinkedHashSet<>();
        String manifestSql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
                + "AND table_name <> 'flyway_schema_history' ORDER BY table_name";
        try (PreparedStatement statement = connection.prepareStatement(manifestSql)) {
            statement.setString(1, SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) actualTables.add(rows.getString(1));
            }
        }
        if (!actualTables.equals(EXPECTED_SOURCE_TABLES)) {
            Set<String> missing = new LinkedHashSet<>(EXPECTED_SOURCE_TABLES);
            missing.removeAll(actualTables);
            Set<String> unexpected = new LinkedHashSet<>(actualTables);
            unexpected.removeAll(EXPECTED_SOURCE_TABLES);
            throw new IllegalStateException(
                    "PostgreSQL source table manifest mismatch for migration V"
                            + EXPECTED_SOURCE_FLYWAY_VERSION + "; missing=" + missing
                            + ", unexpected=" + unexpected
            );
        }

        String historySchema = scalarString(
                connection,
                "SELECT CASE "
                        + "WHEN to_regclass('challengesphere.flyway_schema_history') IS NOT NULL "
                        + "THEN 'challengesphere' "
                        + "WHEN to_regclass('public.flyway_schema_history') IS NOT NULL "
                        + "THEN 'public' ELSE '' END"
        );
        if (!"challengesphere".equals(historySchema) && !"public".equals(historySchema)) {
            throw new IllegalStateException(
                    "A Flyway schema history is required in public or challengesphere"
            );
        }
        String flywayVersion = scalarString(
                connection,
                "SELECT version FROM " + quoted(historySchema)
                        + ".flyway_schema_history WHERE success = TRUE "
                        + "ORDER BY installed_rank DESC LIMIT 1"
        );
        if (!EXPECTED_SOURCE_FLYWAY_VERSION.equals(flywayVersion)) {
            throw new IllegalStateException(
                    "PostgreSQL source Flyway version must be "
                            + EXPECTED_SOURCE_FLYWAY_VERSION + "; got " + flywayVersion
            );
        }
        long failedMigrations = scalarLong(
                connection,
                "SELECT COUNT(*) FROM " + quoted(historySchema)
                        + ".flyway_schema_history WHERE success = FALSE"
        );
        if (failedMigrations != 0) {
            throw new IllegalStateException(
                    "PostgreSQL source Flyway history contains " + failedMigrations
                            + " failed migration row(s)"
            );
        }
    }

    private void validateOperationalCutoverState(Connection connection) throws SQLException {
        Map<String, String> unfinishedPredicates = Map.of(
                "account_deletion_requests", "status <> 'COMPLETED'",
                "account_media_cleanup_queue", "status NOT IN ('DELETED', 'SKIPPED')",
                "provider_token_revocations", "status <> 'COMPLETED'"
        );
        for (Map.Entry<String, String> check : unfinishedPredicates.entrySet()) {
            if (!tableExists(connection, check.getKey())) continue;
            long unfinished = scalarLong(
                    connection,
                    "SELECT COUNT(*) FROM " + quoted(SCHEMA) + '.' + quoted(check.getKey())
                            + " WHERE " + check.getValue()
            );
            if (unfinished != 0) {
                throw new IllegalStateException(
                        "Cutover blocked: " + check.getKey() + " has " + unfinished
                                + " unfinished row(s); drain or resolve them before PLAN/EXECUTE"
                );
            }
        }
    }

    private void validateOfficialChallengeCatalog(Connection connection) throws SQLException {
        if (!tableExists(connection, "official_challenge_templates")) return;
        Set<String> expected = OfficialChallengeCatalog
                .eligibleOn(LocalDate.now(ZoneOffset.UTC))
                .stream()
                .map(OfficialChallengeCatalog.Template::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actual = new LinkedHashSet<>();
        String sql = "SELECT template_key FROM " + quoted(SCHEMA)
                + ".official_challenge_templates WHERE global_cohort_enabled = TRUE"
                + " AND available_through >= CURRENT_DATE ORDER BY rotation_order";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) actual.add(rows.getString(1));
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "The version-controlled official challenge catalog differs from PostgreSQL; "
                            + "source=" + actual + ", artifact=" + expected
            );
        }
    }

    private void importRow(EntitySpec spec, Map<String, Object> source) throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> column : source.entrySet()) {
            Object value = normalizeSqlValue(column.getValue());
            String idTarget = idTarget(spec, column.getKey());
            if (idTarget != null && value != null) value = deterministicId(idTarget, value.toString());
            String property = snakeToCamel(column.getKey());
            properties.put(property, adaptForField(spec.modelClass(), property, value));
        }

        if (spec.modelClass() == User.class) {
            Object legacyUserId = source.get(spec.primaryColumn());
            if (legacyUserId != null) {
                properties.put("legacyId", legacyUserId.toString());
            }
        }

        if ("content_reports".equals(spec.table())) {
            normalizeContentReport(source, properties);
        }

        rewriteEmbeddedIdentifiers(spec, properties);

        Object entity = mapper.convertValue(properties, spec.modelClass());
        if (entity instanceof AuthIdentity identity) {
            String userId = Objects.toString(properties.get("userId"), null);
            identity.setUser(resolveReference(UserRepository.class, userId, User.class));
        } else if (entity instanceof LeaderboardEntry entry) {
            String userId = Objects.toString(properties.get("userId"), null);
            String challengeId = Objects.toString(properties.get("challengeId"), null);
            entry.setUser(resolveReference(UserRepository.class, userId, User.class));
            entry.setChallenge(resolveReference(ChallengeRepository.class, challengeId, Challenge.class));
        }

        Object repository = applicationContext.getBean(spec.repositoryClass());
        Method save = findSaveMethod(repository.getClass(), spec.modelClass());
        try {
            save.invoke(repository, entity);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception exception) throw exception;
            throw wrapped;
        }
    }

    private long importCompletedAccountDeletionRetentions(
            Connection connection,
            String runId,
            MigrationPlan plan
    ) throws SQLException {
        long sourceRows = plan.tableRows().getOrDefault(ACCOUNT_DELETION_REQUESTS_TABLE, 0L);
        TableProgress progress = readTableProgress(
                runId,
                ACCOUNT_DELETION_REQUESTS_TABLE,
                sourceRows,
                plan.schemaFingerprint()
        );
        long processed = progress.processedRows();
        String lastSourceKey = progress.lastSourceKey();
        if (processed > sourceRows) {
            throw new IllegalStateException(
                    "Checkpoint exceeds source count for " + ACCOUNT_DELETION_REQUESTS_TABLE
            );
        }
        if (sourceRows == 0) {
            writeTableProgress(
                    runId,
                    ACCOUNT_DELETION_REQUESTS_TABLE,
                    0,
                    0,
                    plan.schemaFingerprint(),
                    null
            );
            return 0L;
        }

        long importedThisRun = 0L;
        while (processed < sourceRows) {
            List<HistoricalAccountDeletionRetention> records = readAccountDeletionRetentions(
                    connection, lastSourceKey, BATCH_SIZE
            );
            if (records.isEmpty()) {
                throw new IllegalStateException(
                        "Source changed during migration: " + ACCOUNT_DELETION_REQUESTS_TABLE
                );
            }
            for (HistoricalAccountDeletionRetention record : records) {
                importAccountDeletionRetention(record);
                processed++;
                importedThisRun++;
                lastSourceKey = record.sourceKey();
            }
            writeTableProgress(
                    runId,
                    ACCOUNT_DELETION_REQUESTS_TABLE,
                    processed,
                    sourceRows,
                    plan.schemaFingerprint(),
                    lastSourceKey
            );
        }
        return importedThisRun;
    }

    private List<HistoricalAccountDeletionRetention> readAccountDeletionRetentions(
            Connection connection,
            String lastSourceKey,
            int limit
    ) throws SQLException {
        String keyExpression = textKeyExpression("id");
        String sql = "SELECT id, user_id, status, requested_at, processed_at, media_cleanup_count, "
                + "email_identity_hmac, username_identity_hmac, identity_hash_expires_at, "
                + "identity_hash_purged_at FROM " + quoted(SCHEMA) + '.'
                + quoted(ACCOUNT_DELETION_REQUESTS_TABLE)
                + (lastSourceKey == null ? "" : " WHERE " + keyExpression + " > ?")
                + " ORDER BY " + keyExpression + " LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (lastSourceKey != null) statement.setString(parameter++, lastSourceKey);
            statement.setInt(parameter, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<HistoricalAccountDeletionRetention> records = new ArrayList<>();
                while (rows.next()) {
                    String status = rows.getString("status");
                    if (!"COMPLETED".equals(status)) {
                        throw new IllegalStateException(
                                "Only completed account-deletion requests may be imported"
                        );
                    }
                    Object legacyRequestId = rows.getObject("id");
                    Object legacyUserId = rows.getObject("user_id");
                    Timestamp requestedAt = rows.getTimestamp("requested_at");
                    if (legacyRequestId == null || legacyUserId == null || requestedAt == null) {
                        throw new IllegalStateException(
                                "Account-deletion request has missing required audit fields"
                        );
                    }
                    int mediaCleanupCount = rows.getInt("media_cleanup_count");
                    if (rows.wasNull()) {
                        throw new IllegalStateException(
                                "Account-deletion media_cleanup_count is unexpectedly null"
                        );
                    }
                    Timestamp processedAt = rows.getTimestamp("processed_at");
                    Timestamp identityHashExpiresAt = rows.getTimestamp("identity_hash_expires_at");
                    Timestamp identityHashPurgedAt = rows.getTimestamp("identity_hash_purged_at");
                    records.add(new HistoricalAccountDeletionRetention(
                            legacyRequestId.toString(),
                            deterministicId(
                                    "ACCOUNT_DELETION_REQUEST",
                                    legacyRequestId.toString()
                            ),
                            deterministicId("USER", legacyUserId.toString()),
                            normalizedIdentityHmac(rows.getString("email_identity_hmac")),
                            normalizedIdentityHmac(rows.getString("username_identity_hmac")),
                            identityHashExpiresAt == null ? null : identityHashExpiresAt.toInstant(),
                            identityHashPurgedAt == null ? null : identityHashPurgedAt.toInstant(),
                            requestedAt.toInstant(),
                            processedAt == null ? null : processedAt.toInstant(),
                            mediaCleanupCount
                    ));
                }
                return List.copyOf(records);
            }
        }
    }

    private void importAccountDeletionRetention(HistoricalAccountDeletionRetention record) {
        operationalStore.importCompletedAccountDeletionRetention(
                record.requestId(),
                record.userId(),
                record.emailIdentityHmac(),
                record.usernameIdentityHmac(),
                record.identityHashExpiresAt(),
                record.identityHashPurgedAt(),
                record.requestedAt(),
                record.processedAt(),
                record.mediaCleanupCount()
        );
    }

    private long importCompletedAppleAccountEvents(
            Connection connection,
            String runId,
            MigrationPlan plan
    ) throws SQLException {
        long sourceRows = plan.tableRows().getOrDefault(APPLE_ACCOUNT_EVENTS_TABLE, 0L);
        if (!tableExists(connection, APPLE_ACCOUNT_EVENTS_TABLE)) {
            if (sourceRows != 0) {
                throw new IllegalStateException("Apple account event source table disappeared");
            }
            return 0L;
        }

        TableProgress progress = readTableProgress(
                runId,
                APPLE_ACCOUNT_EVENTS_TABLE,
                sourceRows,
                plan.schemaFingerprint()
        );
        long processed = progress.processedRows();
        String lastSourceKey = progress.lastSourceKey();
        if (processed > sourceRows) {
            throw new IllegalStateException(
                    "Checkpoint exceeds source count for " + APPLE_ACCOUNT_EVENTS_TABLE
            );
        }
        if (sourceRows == 0) {
            writeTableProgress(
                    runId,
                    APPLE_ACCOUNT_EVENTS_TABLE,
                    0,
                    0,
                    plan.schemaFingerprint(),
                    null
            );
            return 0L;
        }

        long importedThisRun = 0L;
        while (processed < sourceRows) {
            List<HistoricalAppleAccountEvent> events = readAppleAccountEvents(
                    connection, lastSourceKey, BATCH_SIZE
            );
            if (events.isEmpty()) {
                throw new IllegalStateException(
                        "Source changed during migration: " + APPLE_ACCOUNT_EVENTS_TABLE
                );
            }
            for (HistoricalAppleAccountEvent event : events) {
                operationalStore.importCompletedAppleAccountEvent(
                        event.jti(),
                        event.eventType(),
                        event.subject(),
                        event.eventTime(),
                        event.receivedAt()
                );
                processed++;
                importedThisRun++;
                lastSourceKey = event.sourceKey();
            }
            writeTableProgress(
                    runId,
                    APPLE_ACCOUNT_EVENTS_TABLE,
                    processed,
                    sourceRows,
                    plan.schemaFingerprint(),
                    lastSourceKey
            );
        }
        return importedThisRun;
    }

    private List<HistoricalAppleAccountEvent> readAppleAccountEvents(
            Connection connection,
            String lastSourceKey,
            int limit
    ) throws SQLException {
        String keyExpression = textKeyExpression("jti");
        String sql = "SELECT jti, event_type, subject, event_time, received_at FROM "
                + quoted(SCHEMA) + '.' + quoted(APPLE_ACCOUNT_EVENTS_TABLE)
                + (lastSourceKey == null ? "" : " WHERE " + keyExpression + " > ?")
                + " ORDER BY " + keyExpression + " LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (lastSourceKey != null) statement.setString(parameter++, lastSourceKey);
            statement.setInt(parameter, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<HistoricalAppleAccountEvent> events = new ArrayList<>();
                while (rows.next()) {
                    long eventTimeValue = rows.getLong("event_time");
                    Long eventTime = rows.wasNull() ? null : eventTimeValue;
                    Timestamp receivedAt = rows.getTimestamp("received_at");
                    if (receivedAt == null) {
                        throw new IllegalStateException(
                                "Apple account event received_at is unexpectedly null"
                        );
                    }
                    events.add(new HistoricalAppleAccountEvent(
                            rows.getString("jti"),
                            rows.getString("jti"),
                            rows.getString("event_type"),
                            rows.getString("subject"),
                            eventTime,
                            receivedAt.toInstant()
                    ));
                }
                return List.copyOf(events);
            }
        }
    }

    private <T> T resolveReference(Class<?> repositoryType, String id, Class<T> type) throws Exception {
        if (id == null) throw new IllegalArgumentException(type.getSimpleName() + " reference is missing");
        Object repository = applicationContext.getBean(repositoryType);
        Method findById = repository.getClass().getMethod("findById", String.class);
        Object result = findById.invoke(repository, id);
        if (result instanceof Optional<?> optional && optional.isPresent()) return type.cast(optional.get());
        throw new IllegalStateException(type.getSimpleName() + " reference was not migrated: " + id);
    }

    private void rewriteEmbeddedIdentifiers(EntitySpec spec, Map<String, Object> properties) {
        for (String property : List.of("actionObject", "targetSnapshot")) {
            Object raw = properties.get(property);
            if (!(raw instanceof String json) || json.isBlank()) continue;
            String trimmed = json.strip();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) continue;
            try {
                Object parsed = mapper.readValue(trimmed, Object.class);
                properties.put(property, mapper.writeValueAsString(rewriteEmbeddedValue(parsed, null)));
            } catch (Exception invalidJson) {
                throw new IllegalArgumentException(
                        "Invalid embedded JSON in " + spec.table() + '.' + property,
                        invalidJson
                );
            }
        }
    }

    private void normalizeContentReport(
            Map<String, Object> source,
            Map<String, Object> properties
    ) {
        Object reporterUserId = properties.remove("reporterUserId");
        properties.put("userId", reporterUserId);
        properties.put("description", properties.remove("details"));
        properties.putIfAbsent("active", true);

        String targetType = Objects.toString(properties.get("targetType"), "")
                .strip().toUpperCase(Locale.ROOT);
        String targetNamespace = switch (targetType) {
            case "SUBMISSION" -> "SUBMISSION";
            case "COMMENT" -> "COMMENT";
            case "CHALLENGE" -> "CHALLENGE";
            default -> throw new IllegalArgumentException(
                    "Unsupported content report target type: " + targetType
            );
        };
        Object rawTargetId = source.get("target_id");
        if (rawTargetId == null) {
            throw new IllegalArgumentException("content_reports.target_id is missing");
        }
        String targetId = deterministicId(targetNamespace, rawTargetId.toString());
        properties.put("targetId", targetId);
        if ("SUBMISSION".equals(targetType)) {
            properties.put("submissionId", targetId);
        }
    }

    private Object rewriteEmbeddedValue(Object value, String fieldName) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> rewritten = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                String name = Objects.toString(key);
                rewritten.put(name, rewriteEmbeddedValue(nested, name));
            });
            return rewritten;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(item -> rewriteEmbeddedValue(item, fieldName)).toList();
        }
        String idTarget = EMBEDDED_ID_TARGETS.get(fieldName);
        if (idTarget != null && value != null) {
            return deterministicId(idTarget, value.toString());
        }
        return value;
    }

    private long verifyCanonicalItems(Connection connection, MigrationPlan plan) throws SQLException {
        long verified = 0;
        for (EntitySpec spec : SPECS) {
            long expected = plan.tableRows().getOrDefault(spec.table(), 0L);
            if (expected == 0) continue;
            String sql = "SELECT " + quoted(spec.primaryColumn()) + " FROM " + quoted(SCHEMA) + '.'
                    + quoted(spec.table()) + " ORDER BY " + quoted(spec.primaryColumn());
            try (PreparedStatement statement = streamingStatement(connection, sql)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String idTarget = idTarget(spec, spec.primaryColumn());
                        Object legacy = rows.getObject(1);
                        String id = idTarget == null
                                ? Objects.toString(legacy)
                                : deterministicId(idTarget, Objects.toString(legacy));
                        boolean present = mainTableGateway.get(
                                DynamoDbKeys.key(
                                        spec.entityType() + '#' + id,
                                        DynamoAccessPatterns.META
                                ),
                                true
                        ).isPresent();
                        if (!present) {
                            throw new IllegalStateException(
                                    "Canonical item missing for " + spec.table() + '/' + id
                            );
                        }
                        DynamoProjection collectionProjection = DynamoProjection.of(
                                DynamoAccessPatterns.collectionPk(spec.entityType(), "ALL"),
                                "ITEM#" + id
                        );
                        if (mainTableGateway.get(collectionProjection.key(), true).isEmpty()) {
                            throw new IllegalStateException(
                                    "Collection projection missing for " + spec.table() + '/' + id
                            );
                        }
                        if ("content_reports".equals(spec.table())) {
                            verifyContentReportProjections(id);
                        }
                        verified++;
                    }
                }
            }
        }
        long expectedDomainRows = plan.sourceRows()
                - plan.tableRows().getOrDefault(APPLE_ACCOUNT_EVENTS_TABLE, 0L)
                - plan.tableRows().getOrDefault(ACCOUNT_DELETION_REQUESTS_TABLE, 0L);
        if (verified != expectedDomainRows) {
            throw new IllegalStateException("Canonical verification count mismatch");
        }
        return verified;
    }

    /**
     * Repairs resumable-import drift in the derived active-participant counter,
     * then strongly proves both the fixed counter and every ordered ACTIVE_USER
     * projection against the frozen PostgreSQL snapshot.
     */
    private ParticipantReconciliation reconcileAndVerifyChallengeParticipants(
            Connection connection
    ) throws SQLException {
        ChallengeParticipantCountStore countStore = applicationContext.getBean(
                ChallengeParticipantCountStore.class
        );
        String counterSql = "SELECT c.id, COUNT(uc.id) FILTER (WHERE uc.active = TRUE "
                + "AND COALESCE(uc.banned, FALSE) = FALSE) AS active_count FROM "
                + quoted(SCHEMA) + '.' + quoted("challenges") + " c LEFT JOIN "
                + quoted(SCHEMA) + '.' + quoted("user_challenges")
                + " uc ON uc.challenge_id = c.id GROUP BY c.id ORDER BY c.id";

        long challengeCounterCount = 0L;
        long expectedActiveProjectionCount = 0L;
        try (PreparedStatement statement = streamingStatement(connection, counterSql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String challengeId = deterministicId("CHALLENGE", rows.getObject("id").toString());
                long expected = rows.getLong("active_count");
                countStore.reconcileActiveCount(challengeId, expected);
                long actual = countStore.getActiveCount(challengeId);
                if (actual != expected) {
                    throw new IllegalStateException(
                            "Challenge participant counter mismatch for " + challengeId
                                    + ": expected " + expected + ", found " + actual
                    );
                }
                long projectionCount = countPartition(
                        ChallengeRepositoryKeys.challengePk(challengeId),
                        ChallengeRepositoryKeys.activeMembershipByUserPrefix()
                );
                if (projectionCount != expected) {
                    throw new IllegalStateException(
                            "Active participant projection count mismatch for " + challengeId
                                    + ": expected " + expected + ", found " + projectionCount
                    );
                }
                expectedActiveProjectionCount += expected;
                challengeCounterCount++;
            }
        }

        String membershipSql = "SELECT id, user_id, challenge_id, active, banned FROM "
                + quoted(SCHEMA) + '.' + quoted("user_challenges") + " ORDER BY id";
        long verifiedActiveProjectionCount = 0L;
        try (PreparedStatement statement = streamingStatement(connection, membershipSql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String membershipId = deterministicId(
                        "USER_CHALLENGE",
                        rows.getObject("id").toString()
                );
                String userId = deterministicId("USER", rows.getObject("user_id").toString());
                String challengeId = deterministicId(
                        "CHALLENGE",
                        rows.getObject("challenge_id").toString()
                );
                boolean shouldExist = rows.getBoolean("active") && !rows.getBoolean("banned");
                DynamoDbKey projectionKey = new DynamoDbKey(
                        ChallengeRepositoryKeys.challengePk(challengeId),
                        ChallengeRepositoryKeys.activeMembershipByUserSk(userId, membershipId)
                );
                boolean present = mainTableGateway.get(projectionKey, true).isPresent();
                if (present != shouldExist) {
                    throw new IllegalStateException(
                            "Active participant projection state mismatch for membership "
                                    + membershipId
                    );
                }
                if (present) {
                    verifiedActiveProjectionCount++;
                }
            }
        }
        if (verifiedActiveProjectionCount != expectedActiveProjectionCount) {
            throw new IllegalStateException(
                    "Active participant projection verification mismatch: expected "
                            + expectedActiveProjectionCount + ", verified "
                            + verifiedActiveProjectionCount
            );
        }
        return new ParticipantReconciliation(
                challengeCounterCount,
                verifiedActiveProjectionCount
        );
    }

    private long verifyAccountDeletionRetentions(
            Connection connection,
            MigrationPlan plan
    ) throws SQLException {
        long expected = plan.tableRows().getOrDefault(ACCOUNT_DELETION_REQUESTS_TABLE, 0L);
        long verified = 0L;
        String lastSourceKey = null;
        while (verified < expected) {
            List<HistoricalAccountDeletionRetention> records = readAccountDeletionRetentions(
                    connection, lastSourceKey, BATCH_SIZE
            );
            if (records.isEmpty()) {
                throw new IllegalStateException(
                        "Account-deletion retention verification ended before the source count"
                );
            }
            for (HistoricalAccountDeletionRetention record : records) {
                boolean imported = operationalStore.isAccountDeletionRetentionImported(
                        record.requestId(),
                        record.userId(),
                        record.emailIdentityHmac(),
                        record.usernameIdentityHmac(),
                        record.identityHashExpiresAt(),
                        record.identityHashPurgedAt(),
                        record.requestedAt(),
                        record.processedAt(),
                        record.mediaCleanupCount()
                );
                if (!imported) {
                    throw new IllegalStateException(
                            "Account-deletion retention payload or purge projection mismatch"
                    );
                }
                verified++;
                lastSourceKey = record.sourceKey();
            }
        }
        if (verified != expected) {
            throw new IllegalStateException(
                    "Account-deletion retention verification count mismatch: expected "
                            + expected + ", verified " + verified
            );
        }
        return verified;
    }

    private long verifyCompletedAppleAccountEvents(
            Connection connection,
            MigrationPlan plan
    ) throws SQLException {
        long expected = plan.tableRows().getOrDefault(APPLE_ACCOUNT_EVENTS_TABLE, 0L);
        if (expected == 0) {
            return 0L;
        }
        String sql = "SELECT jti FROM " + quoted(SCHEMA) + '.'
                + quoted(APPLE_ACCOUNT_EVENTS_TABLE) + " ORDER BY jti";
        long verified = 0L;
        try (PreparedStatement statement = streamingStatement(connection, sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String jti = rows.getString(1);
                if (!operationalStore.isAppleAccountEventTerminal(jti)) {
                    throw new IllegalStateException(
                            "Apple account event is missing or non-terminal for historical JTI"
                    );
                }
                verified++;
            }
        }
        if (verified != expected) {
            throw new IllegalStateException(
                    "Apple account event verification count mismatch: expected "
                            + expected + ", verified " + verified
            );
        }
        return verified;
    }

    private long verifyCollections(MigrationPlan plan) {
        long total = 0;
        Map<String, Long> expectedByEntityType = new LinkedHashMap<>();
        for (EntitySpec spec : SPECS) {
            expectedByEntityType.merge(
                    spec.entityType(),
                    plan.tableRows().getOrDefault(spec.table(), 0L),
                    Long::sum
            );
        }
        for (Map.Entry<String, Long> expectation : expectedByEntityType.entrySet()) {
            long expected = expectation.getValue();
            if (expected == 0) continue;
            long actual = countPartition(
                    DynamoAccessPatterns.collectionPk(expectation.getKey(), "ALL"),
                    "ITEM#"
            );
            if (actual != expected) {
                throw new IllegalStateException("Projection verification failed for " + expectation.getKey()
                        + ": expected exactly " + expected + ", found " + actual);
            }
            total += actual;
        }
        return total;
    }

    private void verifyContentReportProjections(String id) {
        ReportRepository repository = applicationContext.getBean(ReportRepository.class);
        Report report = repository.findById(id).orElseThrow(() -> new IllegalStateException(
                "Content report payload missing for " + id
        ));
        for (DynamoProjection projection : repository.projectionsForVerification(report)) {
            if (mainTableGateway.get(projection.key(), true).isEmpty()) {
                throw new IllegalStateException(
                        "Content report projection missing for " + id + " at " + projection.key()
                );
            }
        }
    }

    private long countPartition(String partitionKey, String sortPrefix) {
        long count = 0;
        Map<String, AttributeValue> cursor = Map.of();
        do {
            QueryRequest.Builder query = QueryRequest.builder()
                    .tableName(mainTable)
                    .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                    .expressionAttributeNames(Map.of("#pk", "PK", "#sk", "SK"))
                    .expressionAttributeValues(Map.of(
                            ":pk", AttributeValue.fromS(partitionKey),
                            ":prefix", AttributeValue.fromS(sortPrefix)))
                    .select("COUNT");
            if (!cursor.isEmpty()) query.exclusiveStartKey(cursor);
            var page = mainTableGateway.query(query.build(), true);
            count += page.count();
            cursor = page.lastEvaluatedKey();
        } while (!cursor.isEmpty());
        return count;
    }

    private List<Map<String, Object>> readRows(
            Connection connection, EntitySpec spec, String lastSourceKey, int limit
    ) throws SQLException {
        String keyExpression = textKeyExpression(spec.primaryColumn());
        String sql = "SELECT * FROM " + quoted(SCHEMA) + '.' + quoted(spec.table())
                + (lastSourceKey == null ? "" : " WHERE " + keyExpression + " > ?")
                + " ORDER BY " + keyExpression + " LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (lastSourceKey != null) statement.setString(parameter++, lastSourceKey);
            statement.setInt(parameter, limit);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT), result.getObject(column));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private TableProgress readTableProgress(
            String runId,
            String table,
            long expectedSourceRows,
            String expectedFingerprint
    ) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(mainTable)
                .consistentRead(true)
                .key(key("MIGRATION#" + runId, "CHECKPOINT#TABLE#" + table))
                .build()).item();
        if (item == null || !item.containsKey("processedRowCount")) {
            return new TableProgress(0L, null);
        }
        requireCheckpointValue(item, "sourceRowCount", Long.toString(expectedSourceRows), table);
        requireCheckpointValue(item, "sourceSchemaFingerprint", expectedFingerprint, table);
        requireCheckpointValue(item, "targetMappingVersion", TARGET_MAPPING_VERSION, table);
        long processed = Long.parseLong(item.get("processedRowCount").n());
        AttributeValue lastKeyValue = item.get("lastSourceKey");
        String lastSourceKey = lastKeyValue == null ? null : lastKeyValue.s();
        if (processed < 0L || (processed > 0L && (lastSourceKey == null || lastSourceKey.isBlank()))) {
            throw new IllegalStateException(
                    "Migration checkpoint " + table + " has invalid keyset progress"
            );
        }
        return new TableProgress(processed, lastSourceKey);
    }

    private void writeTableProgress(
            String runId,
            String table,
            long processed,
            long sourceRows,
            String schemaFingerprint,
            String lastSourceKey
    ) {
        if (processed > 0L && (lastSourceKey == null || lastSourceKey.isBlank())) {
            throw new IllegalArgumentException("lastSourceKey is required after the first row");
        }
        Map<String, AttributeValue> item = new HashMap<>(key("MIGRATION#" + runId, "CHECKPOINT#TABLE#" + table));
        item.put("status", AttributeValue.fromS(processed == sourceRows ? "COMPLETED" : "RUNNING"));
        item.put("processedRowCount", AttributeValue.fromN(Long.toString(processed)));
        item.put("sourceRowCount", AttributeValue.fromN(Long.toString(sourceRows)));
        item.put("sourceSchemaFingerprint", AttributeValue.fromS(schemaFingerprint));
        item.put("targetMappingVersion", AttributeValue.fromS(TARGET_MAPPING_VERSION));
        if (lastSourceKey != null) {
            item.put("lastSourceKey", AttributeValue.fromS(lastSourceKey));
        }
        item.put("updatedAt", AttributeValue.fromS(Instant.now().toString()));
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(mainTable).item(item).build());
    }

    private boolean validateResumeCheckpoint(String runId, MigrationPlan plan) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(mainTable)
                .consistentRead(true)
                .key(key("MIGRATION#" + runId, "CHECKPOINT#SUMMARY"))
                .build()).item();
        if (item == null || item.isEmpty()) return false;
        requireCheckpointValue(item, "sourceRowCount", Long.toString(plan.sourceRows()), "summary");
        requireCheckpointValue(item, "sourceSchemaFingerprint", plan.schemaFingerprint(), "summary");
        requireCheckpointValue(item, "targetMappingVersion", TARGET_MAPPING_VERSION, "summary");
        return true;
    }

    /**
     * A new run may only target a genuinely empty main table. Resumption is
     * recognized solely by this run's matching versioned summary checkpoint;
     * this prevents unrelated/stale projections from surviving verification.
     */
    private void requireFreshMainTable() {
        var result = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(mainTable)
                .consistentRead(true)
                .limit(1)
                .projectionExpression("#pk")
                .expressionAttributeNames(Map.of("#pk", DynamoDbKeys.PARTITION_KEY))
                .build());
        if (result.count() != 0 || !result.items().isEmpty()) {
            throw new IllegalStateException(
                    "A new migration run requires an empty DynamoDB main table; "
                            + "resume only with the matching run ID or provision a fresh target"
            );
        }
    }

    private static void requireCheckpointValue(
            Map<String, AttributeValue> item,
            String field,
            String expected,
            String checkpoint
    ) {
        AttributeValue value = item.get(field);
        String actual = value == null ? null : (value.s() != null ? value.s() : value.n());
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Migration checkpoint " + checkpoint + " has " + field + '=' + actual
                            + ", expected " + expected + "; use a new run ID after reviewing the source change"
            );
        }
    }

    private void writeSummary(
            String runId,
            String status,
            MigrationPlan plan,
            long canonicalItems,
            long projectionItems,
            long terminalAppleAccountEvents,
            long verifiedAccountDeletionRetentions,
            long failedItems,
            boolean feedTimelineReady,
            long submissionFanoutChunks,
            long followBackfillChunks,
            boolean challengeParticipantsReady,
            long verifiedChallengeParticipantCounterCount,
            long verifiedActiveParticipantProjectionCount,
            Instant completedAt
    ) {
        Map<String, AttributeValue> item = new HashMap<>(key("MIGRATION#" + runId, "CHECKPOINT#SUMMARY"));
        item.put("status", AttributeValue.fromS(status));
        item.put("sourceRowCount", AttributeValue.fromN(Long.toString(plan.sourceRows())));
        item.put("canonicalItemCount", AttributeValue.fromN(Long.toString(canonicalItems)));
        item.put("projectionItemCount", AttributeValue.fromN(Long.toString(projectionItems)));
        long appleSourceRows = plan.tableRows().getOrDefault(APPLE_ACCOUNT_EVENTS_TABLE, 0L);
        long accountDeletionSourceRows = plan.tableRows()
                .getOrDefault(ACCOUNT_DELETION_REQUESTS_TABLE, 0L);
        item.put(
                "domainSourceRowCount",
                AttributeValue.fromN(Long.toString(
                        plan.sourceRows() - appleSourceRows - accountDeletionSourceRows
                ))
        );
        item.put(
                "appleAccountEventSourceCount",
                AttributeValue.fromN(Long.toString(appleSourceRows))
        );
        item.put(
                "terminalAppleAccountEventCount",
                AttributeValue.fromN(Long.toString(terminalAppleAccountEvents))
        );
        item.put(
                "accountDeletionRetentionSourceCount",
                AttributeValue.fromN(Long.toString(accountDeletionSourceRows))
        );
        item.put(
                "verifiedAccountDeletionRetentionCount",
                AttributeValue.fromN(Long.toString(verifiedAccountDeletionRetentions))
        );
        item.put("failedItemCount", AttributeValue.fromN(Long.toString(failedItems)));
        item.put("sourceSchemaFingerprint", AttributeValue.fromS(plan.schemaFingerprint()));
        item.put("targetMappingVersion", AttributeValue.fromS(TARGET_MAPPING_VERSION));
        item.put("authSessionPolicy", AttributeValue.fromS("FORCED_REAUTH"));
        item.put("feedTimelineReady", AttributeValue.fromBool(feedTimelineReady));
        item.put(
                "pendingSubmissionFanoutCount",
                AttributeValue.fromN(feedTimelineReady ? "0" : "-1")
        );
        item.put(
                "pendingFollowBackfillCount",
                AttributeValue.fromN(feedTimelineReady ? "0" : "-1")
        );
        item.put(
                "submissionFanoutChunkCount",
                AttributeValue.fromN(Long.toString(submissionFanoutChunks))
        );
        item.put(
                "followBackfillChunkCount",
                AttributeValue.fromN(Long.toString(followBackfillChunks))
        );
        item.put(
                "challengeParticipantsReady",
                AttributeValue.fromBool(challengeParticipantsReady)
        );
        item.put(
                "verifiedChallengeParticipantCounterCount",
                AttributeValue.fromN(Long.toString(verifiedChallengeParticipantCounterCount))
        );
        item.put(
                "verifiedActiveParticipantProjectionCount",
                AttributeValue.fromN(Long.toString(verifiedActiveParticipantProjectionCount))
        );
        item.put("completedAt", AttributeValue.fromS((completedAt == null ? Instant.now() : completedAt).toString()));
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(mainTable).item(item).build());
    }

    private void writeError(String runId, Exception failure) {
        String message = Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
        if (message.length() > 1000) message = message.substring(0, 1000);
        Map<String, AttributeValue> item = new HashMap<>(key("MIGRATION#" + runId,
                "ERROR#" + Instant.now().toEpochMilli()));
        item.put("errorType", AttributeValue.fromS(failure.getClass().getName()));
        item.put("message", AttributeValue.fromS(message));
        item.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(mainTable).item(item).build());
    }

    private String schemaFingerprint(Connection connection) throws SQLException {
        String sql = "SELECT table_name, column_name, data_type, is_nullable, ordinal_position "
                + "FROM information_schema.columns WHERE table_schema = ? ORDER BY table_name, ordinal_position";
        StringBuilder signature = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    signature.append(rows.getString(1)).append('|').append(rows.getString(2)).append('|')
                            .append(rows.getString(3)).append('|').append(rows.getString(4)).append('|')
                            .append(rows.getInt(5)).append('\n');
                }
            }
        }
        return hex(digest(signature.toString()));
    }

    private String sourceFingerprint(
            Connection connection,
            Map<String, Long> sourceTableRows
    ) throws SQLException {
        MessageDigest fingerprint = sha256Digest();
        updateFingerprint(fingerprint, "CHALLENGESPHERE_MIGRATION_SOURCE_V2");
        updateFingerprint(fingerprint, schemaFingerprint(connection));

        for (Map.Entry<String, Long> entry : sourceTableRows.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            String table = entry.getKey();
            long expectedRows = entry.getValue();
            updateFingerprint(fingerprint, table);
            updateFingerprint(fingerprint, Long.toString(expectedRows));
            hashSourceTable(connection, table, sourcePrimaryColumn(table), expectedRows, fingerprint);
        }
        return hex(fingerprint.digest());
    }

    /**
     * Hashes a stable, primary-key-ordered source snapshot. Counts and schema
     * alone cannot detect an in-place row edit between two migration attempts.
     */
    private void hashSourceTable(
            Connection connection,
            String table,
            String primaryColumn,
            long expectedRows,
            MessageDigest fingerprint
    ) throws SQLException {
        String sql = "SELECT * FROM " + quoted(SCHEMA) + '.' + quoted(table)
                + " ORDER BY " + quoted(primaryColumn);
        long observedRows = 0;
        try (PreparedStatement statement = streamingStatement(connection, sql)) {
            try (ResultSet rows = statement.executeQuery()) {
                ResultSetMetaData metadata = rows.getMetaData();
                int columnCount = metadata.getColumnCount();
                updateFingerprint(fingerprint, Integer.toString(columnCount));
                for (int column = 1; column <= columnCount; column++) {
                    updateFingerprint(fingerprint, metadata.getColumnLabel(column));
                    updateFingerprint(fingerprint, metadata.getColumnTypeName(column));
                }

                while (rows.next()) {
                    updateFingerprint(fingerprint, "ROW");
                    for (int column = 1; column <= columnCount; column++) {
                        Object value = normalizeSqlValue(rows.getObject(column));
                        try {
                            updateFingerprint(fingerprint, mapper.writeValueAsBytes(value));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                            throw new SQLException(
                                    "Unable to fingerprint " + table + '.'
                                            + metadata.getColumnLabel(column),
                                    failure
                            );
                        }
                    }
                    observedRows++;
                }
            }
        }
        if (observedRows != expectedRows) {
            throw new IllegalStateException(
                    "Source changed while fingerprinting " + table
                            + ": expected " + expectedRows + " rows but read " + observedRows
            );
        }
    }

    private static String sourcePrimaryColumn(String table) {
        if (APPLE_ACCOUNT_EVENTS_TABLE.equals(table)) {
            return "jti";
        }
        if (ACCOUNT_DELETION_REQUESTS_TABLE.equals(table)) {
            return "id";
        }
        return SPECS.stream()
                .filter(spec -> spec.table().equals(table))
                .map(EntitySpec::primaryColumn)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No migration primary column is registered for " + table
                ));
    }

    private static void updateFingerprint(MessageDigest fingerprint, String value) {
        updateFingerprint(fingerprint, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateFingerprint(MessageDigest fingerprint, byte[] value) {
        fingerprint.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        fingerprint.update(value);
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getTables(null, SCHEMA, table, new String[]{"TABLE"})) {
            return result.next();
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Count query returned no row");
            return result.getLong(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Scalar query returned no row");
            return Objects.toString(result.getString(1), "");
        }
    }

    private static String idTarget(EntitySpec spec, String column) {
        if (column.equals(spec.primaryColumn())) {
            if (spec.table().equals("challenge_crew_lifecycle")) return "CHALLENGE";
            if (spec.table().equals("user_subscriptions")) return "USER";
            if (spec.table().equals("subscription_webhook_events")) return null;
            if (spec.table().equals("content_reports")) return "CONTENT_REPORT";
            return spec.entityType();
        }
        return FOREIGN_ID_TARGETS.get(column);
    }

    private Object adaptForField(Class<?> modelClass, String property, Object value) {
        if (value == null) return null;
        Field field = findField(modelClass, property);
        if (field == null) return value;
        if (field.getType() == Instant.class && value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (field.getType() == LocalDate.class && value instanceof java.sql.Date date) return date.toLocalDate();
        if (field.getType() == LocalDateTime.class && value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (Collection.class.isAssignableFrom(field.getType()) && value instanceof String text) {
            if (text.isBlank()) return List.of();
            return List.of(text.split("\\s*,\\s*"));
        }
        return value;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue with the parent type.
            }
        }
        return null;
    }

    private static Method findSaveMethod(Class<?> repositoryClass, Class<?> modelClass) {
        return java.util.Arrays.stream(repositoryClass.getMethods())
                .filter(method -> method.getName().equals("save") && method.getParameterCount() == 1)
                .filter(method -> method.getParameterTypes()[0].isAssignableFrom(modelClass))
                .min(Comparator.comparingInt(method -> method.getParameterTypes()[0] == modelClass ? 0 : 1))
                .orElseThrow(() -> new IllegalStateException(
                        "No compatible save method on " + repositoryClass.getName()
                ));
    }

    private static Object normalizeSqlValue(Object value) throws SQLException {
        if (value instanceof java.sql.Array sqlArray) {
            Object array = sqlArray.getArray();
            int length = Array.getLength(array);
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) values.add(Array.get(array, index));
            return values;
        }
        if (value != null && value.getClass().getName().equals("org.postgresql.util.PGobject")) {
            try {
                return value.getClass().getMethod("getValue").invoke(value);
            } catch (ReflectiveOperationException exception) {
                throw new SQLException("Unable to read PostgreSQL object", exception);
            }
        }
        return value;
    }

    private static String deterministicId(String namespace, String legacyId) {
        String normalized = legacyId == null ? "" : legacyId.strip().toUpperCase(Locale.ROOT);
        if (normalized.matches("[0-9A-HJKMNP-TV-Z]{26}")) return normalized;
        byte[] bytes = digest(namespace + '\u0000' + legacyId);
        byte[] first128 = java.util.Arrays.copyOf(bytes, 16);
        BigInteger value = new BigInteger(1, first128);
        StringBuilder encoded = new StringBuilder(26);
        BigInteger radix = BigInteger.valueOf(32);
        for (int index = 0; index < 26; index++) {
            BigInteger[] division = value.divideAndRemainder(radix);
            encoded.append(CROCKFORD.charAt(division[1].intValue()));
            value = division[0];
        }
        return encoded.reverse().toString();
    }

    private static byte[] digest(String value) {
        return sha256Digest().digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] value) { return java.util.HexFormat.of().formatHex(value); }

    private static String snakeToCamel(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean upper = false;
        for (char character : value.toCharArray()) {
            if (character == '_') {
                upper = true;
            } else if (upper) {
                result.append(Character.toUpperCase(character));
                upper = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String normalizedIdentityHmac(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "Account-deletion identity fingerprint is not a lowercase SHA-256 HMAC"
            );
        }
        return normalized;
    }

    private static String sourceKey(Map<String, Object> row, String primaryColumn) {
        Object value = row.get(primaryColumn);
        if (value == null) {
            throw new IllegalStateException("Source row has a null primary key: " + primaryColumn);
        }
        String sourceKey = value.toString();
        if (sourceKey.isBlank()) {
            throw new IllegalStateException("Source row has a blank primary key: " + primaryColumn);
        }
        return sourceKey;
    }

    private static String textKeyExpression(String column) {
        return "CAST(" + quoted(column) + " AS text) COLLATE \"C\"";
    }

    private static PreparedStatement streamingStatement(Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
        );
        statement.setFetchSize(1_000);
        return statement;
    }

    private static String quoted(String identifier) {
        if (!identifier.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Unsafe SQL identifier");
        return '"' + identifier + '"';
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of("PK", AttributeValue.fromS(pk), "SK", AttributeValue.fromS(sk));
    }

    private static void requireEnabled() {
        if (!"true".equalsIgnoreCase(environment("DYNAMODB_MIGRATION_ENABLED", "false"))) {
            throw new IllegalStateException("DYNAMODB_MIGRATION_ENABLED=true is required");
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static EntitySpec spec(
            String table, String primaryColumn, String entityType, Class<?> model, Class<?> repository
    ) {
        return new EntitySpec(table, primaryColumn, entityType, model, repository);
    }

    private enum MigrationMode { PLAN, EXECUTE }

    private record EntitySpec(
            String table,
            String primaryColumn,
            String entityType,
            Class<?> modelClass,
            Class<?> repositoryClass
    ) {}

    private record HistoricalAccountDeletionRetention(
            String sourceKey,
            String requestId,
            String userId,
            String emailIdentityHmac,
            String usernameIdentityHmac,
            Instant identityHashExpiresAt,
            Instant identityHashPurgedAt,
            Instant requestedAt,
            Instant processedAt,
            int mediaCleanupCount
    ) {}

    private record HistoricalAppleAccountEvent(
            String sourceKey,
            String jti,
            String eventType,
            String subject,
            Long eventTime,
            Instant receivedAt
    ) {}

    private record MigrationPlan(long sourceRows, Map<String, Long> tableRows, String schemaFingerprint) {}

    private record TableProgress(long processedRows, String lastSourceKey) {}

    private record ParticipantReconciliation(
            long challengeCounterCount,
            long activeProjectionCount
    ) {}
}
