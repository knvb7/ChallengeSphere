# ChallengeSphere DynamoDB migration runbook

## Purpose and safety boundary

This runbook moves the ChallengeSphere backend from PostgreSQL and Redis to two
DynamoDB tables. It is written for a maintenance-window cutover with no active
customer traffic. It is not a zero-downtime dual-write procedure.

The checked-in tooling does not deploy, cut over, snapshot, or decommission
anything by itself:

- `infrastructure/dynamodb/template.yaml` is a deployable CloudFormation
  template, but an operator must explicitly deploy it and enable stack
  termination protection.
- `scripts/migrate-postgres-to-dynamodb.sh` defaults to `--plan`. Its
  `--execute` mode requires an approved AWS account, disabled source writes, a
  stable run ID, and an exact confirmation phrase.
- `scripts/verify-dynamodb-cutover.sh` is read-only in every phase. Even its
  `decommission-readiness` phase only verifies final snapshots and emits a
  readiness result. It contains no delete, update, or traffic-switch API call.

Do not remove RDS or ElastiCache until the rollback window has ended, the
read-only decommission audit passes, billing has been reviewed, and a separate
human-approved change carries out the deletion.

## Provisioned data plane

Deploy `infrastructure/dynamodb/template.yaml` in the same region as the
backend. The production default is `ap-south-1`.

### Durable table: `challengesphere-main`

- On-demand (`PAY_PER_REQUEST`) Standard table class.
- String `PK` and `SK` primary keys.
- Six sparse, all-attribute generic indexes named `GSI1` through `GSI6`; each
  has string `<index>PK` and `<index>SK` keys.
- `NEW_AND_OLD_IMAGES` DynamoDB Stream for real materialized-view consumers.
- KMS server-side encryption using the DynamoDB AWS-owned key.
- 35-day point-in-time recovery, deletion protection, CloudFormation
  `Retain`, and daily AWS Backup recovery points.

The generic GSI names are physical schema, not business meaning. Every item
type owns which sparse index attributes it writes. Runtime and migration code
must share those access-pattern constants; do not infer an index from a SQL
index with a similar name.

### Ephemeral table: `challengesphere-ephemeral`

- On-demand Standard table with string `PK`/`SK` and two sparse generic GSIs.
- Numeric epoch-seconds `expiresAt` TTL and a `NEW_IMAGE` Stream.
- The same encryption, deletion protection, PITR, retention, and daily backup
  controls as the durable table.

DynamoDB TTL deletion is asynchronous. Authentication, rate-limit, quota,
invite, idempotency, upload-grant, and job-claim reads must reject an item whose
numeric `expiresAt` is at or before the current epoch time even if DynamoDB has
not deleted it yet.

### Runtime access patterns and latency boundary

Domain writes store a canonical item plus the projections needed by each read
path in one optimistic DynamoDB transaction. Contended workflows—relationship
transitions and counters, challenge slots, invite redemption, glory/comment
counters, upload quotas, subscription webhooks, and uniqueness—use conditional
or transactional writes rather than read-then-write assumptions.

User-facing collection APIs use signed opaque cursors bound to their viewer,
filters, ordering, and limit. Followers/following, challenges, participants,
notifications, leaderboards, user/challenge submissions, and feed continuations
advance a DynamoDB last-evaluated key instead of loading a complete partition
and applying a numeric offset. Filtered social and feed reads inspect at most
500 candidates per request, batch-hydrate related records, and return a cursor
when more candidates remain.

The feed layouts are deliberately bounded:

- FOLLOWING is a durable per-viewer main-table timeline. A new submission
  creates resumable fanout work; a new follow creates resumable historical
  backfill work. Workers advance 100 records under leases and retry unprocessed
  batch writes before moving their durable cursor.
- CONNECTIONS reads that same timeline and batch-checks reciprocal relationships
  for only the current candidate page.
- EXPLORE performs a bounded merge over eight stable public-feed shards.
- TRENDING performs a bounded merge over the current and prior ISO-week shard
  buckets, then enforces the cursor's fixed seven-day cutoff. It never reads
  older weekly history for that request.

Username search merges four existing lexicographic search shards and an exact
email lookup. This intentionally orders by normalized username rather than the
legacy follower-ranked search. Interest suggestions index the first 20 unique,
normalized interests; the full domain value is retained. Challenge discovery
likewise indexes the first 20 distinct normalized hashtags and first 18
distinct normalized categories in their stored source order while retaining
the complete CSV values. Creator and tag discovery stay newest-first; joined
and favorite collections use membership/favorite activity time so their
bounded streams can be merged without materializing full history. Challenge
participant pages use an active-membership projection ordered by user ID,
while a fixed strongly read counter supplies the global active participant
total. Blocked-user management pages are likewise ordered by user ID rather
than block creation time so they can continue directly from the existing
active-edge key.

Submission preview cards inspect at most 500 candidates and return at most four
images. `totalCount` is exact only when `totalCountExact=true`; when
`hasMore=true`, it is a lower bound for the inspected window. This explicit
contract prevents a small preview request from hiding a full challenge-history
walk.

These designs remove table scans and unbounded hot-path materialization, but do
not constitute a latency benchmark. Rehearsal must record p50/p95/p99 latency,
throttling, consumed request units, and cost for representative data volume and
traffic before production approval.

### IAM and stream wiring

The runtime managed policy allows only exact-table/index data-plane operations:
get, query, put, update, delete, batches, and transactions. It intentionally
omits `Scan`, PartiQL, table administration, exports, backups, and stream reads.
By default it attaches to the established `ChallengeSphereBackendSSMRole`; set
`BackendRuntimeRoleName` to an empty value only for a non-production staging
exercise. The production cutover verifier fails closed unless the policy is
attached to a concrete role, its actions/resources exactly match the two
tables, and effective IAM simulation denies scans, administration, PartiQL,
and access to unrelated table and index ARNs. The simulation also proves that
all eight approved indexes allow `Query` while denying `Scan`.

The template also creates an unattached stream-consumer managed policy scoped
to the two current stream ARNs. Attach it only to a real deployed consumer
role. No placeholder Lambda, event-source mapping, or DLQ is created because
there is no deployable consumer artifact in this stack. Each future consumer
stack must provide:

- an idempotent Lambda or long-running consumer with a bounded retry policy;
- a real SQS dead-letter queue with encryption and retention;
- an event-source mapping that starts from `TRIM_HORIZON` for initial derived
  model construction and uses partial-batch failure reporting;
- alarms for Lambda errors/throttles, DLQ visible messages, and
  `IteratorAgeMilliseconds` tied to that actual consumer.

### Backups and alarms

Both tables have 35-day point-in-time recovery. With
`EnableScheduledBackups=true`, AWS Backup also takes a daily recovery point at
01:30 Asia/Kolkata and retains it for `BackupRetentionDays` (35 by default).
The vault, tables, and managed policies use CloudFormation retain policies.

Baseline CloudWatch alarms cover table and GSI throttles, request-unit cost
guardrails, conditional-check failures, operation-dimensioned DynamoDB system
errors, and failed AWS Backup jobs. Set `AlarmNotificationTopicArn` to an
existing SNS topic to route ALARM/OK actions. The request-unit thresholds are
initial guardrails, not capacity limits; review them against Cost Explorer and
30 days of observed traffic.

## Deploy the CloudFormation stack

Use an approved account ID from the production change record. Do not derive the
expected value from the same live credentials being checked, because that would
defeat the wrong-account guard.

```bash
export AWS_REGION=ap-south-1
export EXPECTED_AWS_ACCOUNT_ID='<approved-12-digit-account-id>'
export DYNAMODB_STACK_NAME='challengesphere-dynamodb-prod'

ACTUAL_AWS_ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
test "$ACTUAL_AWS_ACCOUNT_ID" = "$EXPECTED_AWS_ACCOUNT_ID"

aws cloudformation validate-template \
  --region "$AWS_REGION" \
  --template-body file://infrastructure/dynamodb/template.yaml

aws cloudformation deploy \
  --region "$AWS_REGION" \
  --stack-name "$DYNAMODB_STACK_NAME" \
  --template-file infrastructure/dynamodb/template.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --no-fail-on-empty-changeset \
  --parameter-overrides \
    Environment=prod \
    MainTableName=challengesphere-main \
    EphemeralTableName=challengesphere-ephemeral \
    BackendRuntimeRoleName=ChallengeSphereBackendSSMRole \
    EnableScheduledBackups=true \
    BackupRetentionDays=35 \
    AlarmNotificationTopicArn='<existing-sns-topic-arn>'

aws cloudformation update-termination-protection \
  --region "$AWS_REGION" \
  --stack-name "$DYNAMODB_STACK_NAME" \
  --enable-termination-protection
```

If no SNS topic is ready, pass `AlarmNotificationTopicArn=`. The alarms still
exist, but cutover approval must record who will watch them and when routing
will be added.

After deployment, record all stack outputs in the change record: table names,
table ARNs, stream ARNs, runtime policy ARN, stream-consumer policy ARN, backup
vault name, and backup plan ID.

### Optional VPC gateway endpoint

EC2 in private subnets can reach DynamoDB through a DynamoDB gateway VPC
endpoint rather than NAT. Gateway endpoints do not require a security group;
associate every backend route table and keep the endpoint policy scoped to the
two table ARNs (including `/index/*`). Verify S3 and other existing routes are
unchanged. Endpoint policy is an additional boundary and does not replace the
runtime role policy.

Before creating one, resolve the exact VPC and route-table IDs from the running
backend instance profile and subnets. Use the regional service name
`com.amazonaws.${AWS_REGION}.dynamodb`. Do not guess IDs or repurpose an
endpoint from another workload. The endpoint belongs in the owning network
stack, not in this data-plane stack, so route-table lifecycle remains with the
VPC.

## Migration runner contract

The packaged Spring Boot JAR must contain:

```text
BOOT-INF/classes/com/ironside/challengesphere/migration/DynamoDbMigrationRunner.class
```

It must also be packaged with the `dynamodb-migration` Maven profile so the
PostgreSQL JDBC driver is present. The normal runtime artifact deliberately
omits that driver, and the launcher rejects a normal artifact before opening a
source connection.

The shell orchestrator passes the following contract:

- environment: `DYNAMODB_MIGRATION_ENABLED=true`, mode `PLAN` or `EXECUTE`,
  stable `DYNAMODB_MIGRATION_RUN_ID`, both DynamoDB table names, AWS region,
  and source JDBC settings through `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`;
- arguments: `--spring.main.web-application-type=none`,
  `--app.scheduling.enabled=false`, `--app.migration.run=true`, mode, run ID,
  and source schema;
- the migration process must keep every application scheduler disabled so it
  cannot become an accidental DynamoDB writer during a long import;
- EXECUTE rejects every DynamoDB endpoint override and performs its own STS
  account check using the exact AWS credential provider shared with the Java
  DynamoDB client. The launcher also pins region, table names, and optional
  profile as command-line properties so ambient Spring profiles cannot retarget
  the writer after the CLI preflight;
- PLAN must make no DynamoDB writes;
- EXECUTE must use conditional/idempotent writes and persist resumable
  checkpoints under the run partition in the main table;
- every table checkpoint stores the last source primary key and resumes with a
  PostgreSQL keyset predicate, avoiding growing `OFFSET` work on large tables;
- checkpoints bind to target mapping version `2026-08-22-v3` as well as the
  frozen source fingerprint. A missing or different mapping version requires a
  new reviewed run ID, so completed rows cannot silently skip a newer
  projection layout;
- a run ID with no matching summary performs a single-item, strongly consistent
  offline `Scan` and requires the main table to be empty. A populated table can
  only be resumed through its exact run ID/fingerprint/mapping checkpoint; use
  a freshly provisioned target for a different run;
- the runner must never scan DynamoDB request paths and must retry all
  `UnprocessedItems` with bounded exponential backoff;
- rerunning the same run ID must converge without minting different ULIDs or
  duplicating relationship/projection items;
- EXECUTE reconciles every challenge's active-participant counter from the
  frozen PostgreSQL snapshot, strongly checks the exact projection count per
  challenge, and strongly checks presence or absence of the ordered projection
  for every source membership. This repairs a resumed run whose canonical
  membership already existed before the derived counter was introduced;
- before VERIFIED, EXECUTE drains every pending submission timeline fanout.
  Because the source is frozen and those fanouts include every currently active
  follower, it then completes redundant per-follow historical markers without
  rewriting the same viewer/submission history a second time. Both pending
  partitions must strongly count to zero;
- the source fingerprint covers the ordered contents of every migrated row as
  well as schema and counts, and the runner reads those rows in a PostgreSQL
  repeatable-read transaction. An in-place source edit therefore blocks resume
  even when table counts are unchanged;
- the source must be the explicitly approved database name, have the exact
  ChallengeSphere V53 table manifest, report Flyway version 53, and contain no
  failed Flyway history rows. Missing
  tables—including an empty `apple_account_events` table—or unexpected tables
  block rehearsal and execution;
- immediately after import and DynamoDB verification, EXECUTE starts a fresh
  PostgreSQL repeatable-read transaction and recomputes the complete content
  fingerprint. The summary cannot become `VERIFIED` if a source commit became
  visible during the import.

Legacy numeric primary and foreign keys are deterministically mapped to ULIDs.
The API contract now treats every domain identifier as an opaque string. Verify
the deployed Android, iOS, and web clients do not parse IDs as JSON numbers or
store them in numeric fields before switching backend traffic.
Both legacy `reports` and the newer `content_reports` safety evidence are
migrated; content report IDs use a separate deterministic namespace and target,
reviewer, and embedded JSON identifiers are remapped. The importer verifies
each canonical item and its exact collection projection, plus every required
content-report queue, target, reporter, and uniqueness projection. Resuming an
existing run ID is rejected if either the source row counts or schema
fingerprint changed.

Historical `apple_account_events` rows are part of the source totals and source
fingerprint. EXECUTE checkpoints that table independently and imports each JTI
as a durable `COMPLETED` operational item because the legacy row committed in
the same PostgreSQL transaction as its account-event side effects. Before the
summary can become `VERIFIED`, the runner performs a strongly consistent read
for every source JTI and rejects any missing, claimable, leased, or otherwise
non-terminal item. This preserves server-notification replay protection across
cutover; these rows must not be omitted or converted into pending work.

Completed historical `account_deletion_requests` are also counted,
fingerprinted, checkpointed, and strongly verified. Their deterministic request
and user IDs, keyed email/username HMACs, expiry and purge timestamps, audit
times, and media-cleanup counts are preserved. Unexpired identity-retention
records get the exact purge-due projection consumed by the DynamoDB retention
scheduler; records whose HMACs were already purged remain audit-only.

PLAN and EXECUTE also fail closed while account-deletion, account-media-cleanup,
or provider-token-revocation rows remain unfinished. The runtime official
challenge catalog is version-controlled; its enabled keys must exactly match
the source catalog before migration starts.

Existing S3 objects are not copied: migrated users retain a non-public
`legacyId` alias so old `user_<numeric-id>` profile and owned-media namespaces
remain readable and eligible for account-deletion cleanup, while every new
upload is written under the ULID namespace.

Authentication tokens are intentionally not copied. Token hashes cannot repair
the numeric `uid` claim inside already-issued JWTs, so cutover is a planned
global logout: all access, refresh, password-reset, and verification sessions
become invalid and users must authenticate again. Approve the user message and
support plan before setting `FORCED_REAUTH_ACCEPTED=true`; the verifier refuses
pre-cutover readiness without it. Pre-cutover verification also performs a
single-item, strongly consistent operational probe and requires the entire
ephemeral table to be empty. This is the only intentional `Scan` in the
migration verifier; EXECUTE also performs the same bounded probe against the
main table before a brand-new run. Both run with the separate migration/operator
identity, never the backend runtime role or a request path. A reused target
containing any domain, projection, session, rate-limit, quota, or claim item is
rejected.

Migrated RevenueCat subscriptions retain their existing
`cs_user_<numeric-id>` App User ID. Webhook aliases containing that legacy ID
resolve through the user's fixed `legacyId` projection, and the API keeps
returning the retained provider ID. Do not switch migrated customers to the
ULID App User ID during the rollback window. Afterward, use RevenueCat's
documented identify/alias or transfer workflow, confirm entitlement parity,
then update the stored App User ID in a separately approved operation.

The runner's authoritative completion item is:

```text
PK = MIGRATION#<runId>
SK = CHECKPOINT#SUMMARY
```

It contains `status` (`RUNNING`, `FAILED`, or `VERIFIED`), numeric
`sourceRowCount`, `canonicalItemCount`, `projectionItemCount`, numeric
`domainSourceRowCount`, `appleAccountEventSourceCount`,
`terminalAppleAccountEventCount`, `accountDeletionRetentionSourceCount`,
`verifiedAccountDeletionRetentionCount`, `failedItemCount`, `completedAt`,
`sourceSchemaFingerprint`, `targetMappingVersion=2026-08-22-v3`,
`authSessionPolicy=FORCED_REAUTH`, feed-timeline readiness/pending/chunk fields,
and challenge-participant readiness/counter/projection fields. Cutover needs
`status=VERIFIED`, `failedItemCount=0`, the exact mapping version, exact Apple
and account-deletion source/verified counts, `feedTimelineReady=true` with both
pending counts zero, and `challengeParticipantsReady=true`. The runner derives
these readiness fields only after strongly consistent checks; the cutover
verifier requires all of them.

## Rehearsal and cutover

### 1. Package and plan

Build the application without running automated tests, then point the script at
the exact reviewed artifact. The workspace testing policy prohibits writing or
running automated tests for this task; compilation, CloudFormation validation,
static shell checks, and read-only AWS inspection are the allowed verification
methods.

```bash
./mvnw -Pdynamodb-migration -DskipTests -Dmaven.test.skip=true package

export AWS_REGION=ap-south-1
export EXPECTED_AWS_ACCOUNT_ID='<approved-12-digit-account-id>'
export DB_URL='jdbc:postgresql://<source-host>:5432/<database>?sslmode=require'
export DB_USERNAME='<migration-user>'
export DB_PASSWORD='<source-password>'
export EXPECTED_SOURCE_DATABASE_NAME='<approved-database-name>'

scripts/migrate-postgres-to-dynamodb.sh \
  --plan \
  --jar target/<reviewed-application>.jar
```

Prefer a short-lived, read-only PostgreSQL migration principal for PLAN. Keep
the password in the environment or an approved secret provider; never place it
on the command line or in Git.

For RDS IAM authentication, omit a static password and set:

```bash
export DB_IAM_AUTH_ENABLED=true
export DB_IAM_AUTH_HOSTNAME='<approved-rds-writer-host>'
export DB_IAM_AUTH_PORT=5432
```

The script generates an RDS auth token with the already verified AWS identity.

### 2. Enter the maintenance window

1. Disable external writes at the API/load-balancer boundary.
2. Stop all scheduled writers, notification writers, moderation workers, and
   administrative jobs. Reads may remain available only if they cannot write
   last-login, counters, sessions, or analytics.
3. Enforce a database-level write fence: revoke/disable every application and
   worker writer credential (or apply the reviewed equivalent at the network or
   database layer), then confirm there are no unexpected writer sessions. The
   environment flag is an approval gate, not the fence itself.
4. Drain or resolve every unfinished account-deletion, media-cleanup, and
   provider-token-revocation row. PLAN/EXECUTE will reject a nonzero queue.
5. Approve the forced-logout user communication and support plan. Existing JWT,
   refresh, password-reset, and verification sessions will not survive cutover.
6. Confirm migrated RevenueCat users will continue using their retained numeric
   App User IDs; do not run an identity transfer during the rollback window.
7. Create and read back an encrypted manual RDS cluster snapshot. Record its
   exact ID and source cluster.
8. Create and read back an ElastiCache snapshot. Record its exact name and
   source replication group.
9. Choose one stable run ID, such as the approved change ID. Never use a new run
   ID to resume the same migration.

### 3. Execute or resume the idempotent migration

```bash
export SOURCE_WRITES_DISABLED=true
export FORCED_REAUTH_ACCEPTED=true
export DYNAMODB_MIGRATION_RUN_ID='<approved-change-id>'

scripts/migrate-postgres-to-dynamodb.sh \
  --execute \
  --jar target/<reviewed-application>.jar
```

The script prints the exact confirmation phrase. For automation, supply that
same value through `--confirm`; do not weaken or bypass the phrase. A process
failure is resumed with the same command and same run ID only after inspecting
the runner checkpoint and error record.

### 4. Prove pre-cutover readiness

```bash
export DYNAMODB_STACK_NAME='challengesphere-dynamodb-prod'
export FORCED_REAUTH_ACCEPTED=true

scripts/verify-dynamodb-cutover.sh \
  --phase pre-cutover \
  --stack "$DYNAMODB_STACK_NAME" \
  --run-id "$DYNAMODB_MIGRATION_RUN_ID"
```

This checks stack termination protection, retained/on-demand/encrypted/active
tables and GSIs, exact 35-day PITR, TTL, the scheduled backup rule's exact
retention/vault/schedule/timezone and exact two-table selection, attached
runtime IAM boundaries, every expected alarm, an empty fresh ephemeral table,
and the strongly consistent VERIFIED checkpoint. It then requires an exact
readiness-only confirmation. A READY result authorizes a separate traffic
change; the verifier itself does not make that change.

### 5. Switch the application

Deploy the exact reviewed DynamoDB backend artifact with the stack-output table
names and region. Remove JDBC/Redis variables only after confirming the new
artifact does not require them at startup. Keep source RDS and ElastiCache
running but write-frozen.

Before restoring traffic, manually verify:

- registration and fresh password/provider login; confirm a pre-cutover access
  and refresh token is rejected and a new post-cutover token succeeds;
- uniqueness, follow/block/privacy authorization, challenge membership, and
  invite redemption;
- submissions, comments, glories, feeds, search, leaderboards, notifications,
  moderation, and account deletion;
- opaque cursor continuation without duplicates or skips;
- username search's documented lexicographic order, capped interest/tag
  indexing, preview `totalCountExact`/`hasMore` behavior, participant totals,
  and participant user-ID ordering;
- expired ephemeral items are rejected before physical TTL deletion;
- stale feed/search projections cannot disclose blocked, private, inactive, or
  moderated content.

Record the deployed artifact checksum, application health evidence, critical
flow evidence, and cutover timestamp. Then run:

```bash
export APPLICATION_DYNAMODB_HEALTH_CONFIRMED=true
export CUTOVER_COMPLETED_AT_UTC='<YYYY-MM-DDTHH:MM:SSZ>'

scripts/verify-dynamodb-cutover.sh \
  --phase post-cutover \
  --stack "$DYNAMODB_STACK_NAME" \
  --run-id "$DYNAMODB_MIGRATION_RUN_ID"
```

## Rollback and decommissioning

Keep the RDS cluster, ElastiCache replication group, both final snapshots, and
the DynamoDB tables for at least seven days. During this window, watch API
latency/error rates, throttles, system errors, conditional failures, stream lag
once consumers exist, DLQ depth, AWS Backup jobs, and request-unit cost.

Rollback is not lossless after DynamoDB writes begin because this procedure
does not dual-write to PostgreSQL. To roll back:

1. disable API and worker writes again;
2. preserve the DynamoDB state and record the incident timestamp;
3. reconcile any post-cutover DynamoDB-only mutations before returning users to
   PostgreSQL, or explicitly accept their loss in the incident/change record;
4. redeploy the last PostgreSQL/Redis artifact and source configuration;
5. manually verify critical flows before restoring traffic.

After the rollback window and fresh final snapshots, run the read-only audit:

```bash
export RDS_CLUSTER_IDENTIFIER='<source-cluster-id>'
export FINAL_RDS_SNAPSHOT_ID='<encrypted-manual-snapshot-id>'
export ELASTICACHE_REPLICATION_GROUP_ID='<source-replication-group-id>'
export FINAL_ELASTICACHE_SNAPSHOT_NAME='<available-snapshot-name>'
export SOURCE_FREEZE_AT_UTC='<YYYY-MM-DDTHH:MM:SSZ>'
export ROLLBACK_WINDOW_END_UTC='<YYYY-MM-DDTHH:MM:SSZ>'

scripts/verify-dynamodb-cutover.sh \
  --phase decommission-readiness \
  --stack "$DYNAMODB_STACK_NAME" \
  --run-id "$DYNAMODB_MIGRATION_RUN_ID"
```

The audit requires both manual snapshots to have been captured at or after
`SOURCE_FREEZE_AT_UTC` and compares their engine, encryption/KMS, source, and
node metadata against the still-running RDS and ElastiCache resources. The
output is evidence for a separate decommission change. That later change
must explicitly name the RDS and ElastiCache resources, retain final encrypted
snapshots, compare before/after monthly cost, and receive its own approval. Do
not disable DynamoDB deletion protection or delete the retained CloudFormation
stack as part of database decommissioning.
