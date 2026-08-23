#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly RUNNER_MARKER='BOOT-INF/classes/com/ironside/challengesphere/migration/DynamoDbMigrationRunner.class'
readonly POSTGRES_DRIVER_PATTERN='^BOOT-INF/lib/postgresql-[^/]+\.jar$'
readonly EXPECTED_SOURCE_FLYWAY_VERSION='53'
readonly EXPECTED_SOURCE_TABLES_CSV='account_deletion_requests,account_media_cleanup_queue,apple_account_events,auth_identities,auth_tokens,challenge_crew_invites,challenge_crew_lifecycle,challenge_invites,challenge_peer_nudges,challenge_peer_reminder_preferences,challenges,comments,content_reports,feature_requests,follows,glories,invite_usages,leaderboard_entries,moderation_flags,notifications,official_challenge_templates,provider_token_revocations,push_device_tokens,reports,submissions,subscription_webhook_events,user_blocks,user_challenge_milestones,user_challenges,user_preferences,user_reports,user_subscriptions,users'

mode='plan'
app_jar="${APP_JAR:-}"
aws_region_value="${AWS_REGION:-${AWS_DEFAULT_REGION:-}}"
aws_profile_value="${AWS_PROFILE:-}"
main_table="${DYNAMODB_MAIN_TABLE_NAME:-challengesphere-main}"
ephemeral_table="${DYNAMODB_EPHEMERAL_TABLE_NAME:-challengesphere-ephemeral}"
confirmation=''

usage() {
  cat <<'USAGE'
Usage:
  scripts/migrate-postgres-to-dynamodb.sh [--plan | --execute] [options]

Modes:
  --plan       Run AWS/source preflight and the packaged migration runner in PLAN mode.
               This is the default and must not write DynamoDB items.
  --execute    Run the idempotent migration runner in EXECUTE mode. Requires all
               production safety gates and an exact confirmation phrase.

Options:
  --jar PATH             Packaged Spring Boot JAR containing DynamoDbMigrationRunner.
  --region REGION        AWS region; otherwise AWS_REGION/AWS_DEFAULT_REGION is required.
  --profile PROFILE      Optional AWS CLI/profile-chain profile.
  --main-table NAME      Durable table (default: challengesphere-main).
  --ephemeral-table NAME TTL table (default: challengesphere-ephemeral).
  --confirm PHRASE       Non-interactive exact confirmation for --execute.
  --help                 Show this help.

Required environment:
  DB_URL                 Source JDBC URL (jdbc:postgresql://...).
  DB_USERNAME            Source PostgreSQL user. DB_PASSWORD stays in the environment.
  EXPECTED_SOURCE_DATABASE_NAME Exact PostgreSQL database approved for migration.
  EXPECTED_AWS_ACCOUNT_ID  Required for --execute; compared with STS caller identity.
  DYNAMODB_MIGRATION_RUN_ID Stable run ID for resumable/idempotent checkpoints.
  SOURCE_WRITES_DISABLED Must be exactly true for --execute.
  FORCED_REAUTH_ACCEPTED Must be exactly true for --execute after the planned
                         global-logout communication is approved.

RDS IAM authentication is supported with DB_IAM_AUTH_ENABLED=true plus
DB_IAM_AUTH_HOSTNAME, DB_IAM_AUTH_PORT, and DB_USERNAME. The generated token is
kept in process environment and is never printed.
USAGE
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

while (($# > 0)); do
  case "$1" in
    --plan)
      mode='plan'
      shift
      ;;
    --execute)
      mode='execute'
      shift
      ;;
    --jar)
      (($# >= 2)) || die '--jar requires a path'
      app_jar="$2"
      shift 2
      ;;
    --region)
      (($# >= 2)) || die '--region requires a value'
      aws_region_value="$2"
      shift 2
      ;;
    --profile)
      (($# >= 2)) || die '--profile requires a value'
      aws_profile_value="$2"
      shift 2
      ;;
    --main-table)
      (($# >= 2)) || die '--main-table requires a value'
      main_table="$2"
      shift 2
      ;;
    --ephemeral-table)
      (($# >= 2)) || die '--ephemeral-table requires a value'
      ephemeral_table="$2"
      shift 2
      ;;
    --confirm)
      (($# >= 2)) || die '--confirm requires a value'
      confirmation="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

for dependency in aws jq psql java jar; do
  require_command "$dependency"
done

[[ -n "$aws_region_value" ]] || die 'Set AWS_REGION/AWS_DEFAULT_REGION or pass --region.'
[[ "$aws_region_value" =~ ^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$ ]] || die "Invalid AWS region: $aws_region_value"
[[ "$main_table" =~ ^[A-Za-z0-9_.-]{3,255}$ ]] || die "Invalid main table name: $main_table"
[[ "$ephemeral_table" =~ ^[A-Za-z0-9_.-]{3,255}$ ]] || die "Invalid ephemeral table name: $ephemeral_table"
[[ "$main_table" != "$ephemeral_table" ]] || die 'Main and ephemeral table names must differ.'

if [[ -z "$app_jar" ]]; then
  shopt -s nullglob
  all_candidate_jars=("$PROJECT_DIR"/target/challengesphere-*.jar)
  shopt -u nullglob
  candidate_jars=()
  for candidate_jar in "${all_candidate_jars[@]}"; do
    [[ "$candidate_jar" == *.original ]] || candidate_jars+=("$candidate_jar")
  done
  ((${#candidate_jars[@]} == 1)) || die 'Pass --jar PATH; exactly one target/challengesphere-*.jar was not found.'
  app_jar="${candidate_jars[0]}"
fi
[[ -f "$app_jar" && -r "$app_jar" ]] || die "Migration JAR is not readable: $app_jar"
jar tf "$app_jar" | grep -Fx "$RUNNER_MARKER" >/dev/null || die "JAR does not contain required migration runner: $RUNNER_MARKER"
jar tf "$app_jar" | grep -Eq "$POSTGRES_DRIVER_PATTERN" \
  || die 'JAR does not contain the migration-only PostgreSQL driver; package with -Pdynamodb-migration.'

[[ -n "${DB_URL:-}" ]] || die 'DB_URL is required.'
[[ "$DB_URL" == jdbc:postgresql://* ]] || die 'DB_URL must start with jdbc:postgresql://.'
[[ -n "${DB_USERNAME:-}" ]] || die 'DB_USERNAME is required.'
[[ "${EXPECTED_SOURCE_DATABASE_NAME:-}" =~ ^[A-Za-z0-9_.-]{1,63}$ ]] \
  || die 'EXPECTED_SOURCE_DATABASE_NAME is required and must be a simple PostgreSQL database name.'

aws_cli=(aws)
if [[ -n "$aws_profile_value" ]]; then
  aws_cli+=(--profile "$aws_profile_value")
fi
aws_cli+=(--region "$aws_region_value")

identity_json="$("${aws_cli[@]}" sts get-caller-identity --output json)" || die 'Unable to resolve AWS caller identity.'
actual_account_id="$(jq -er '.Account | select(test("^[0-9]{12}$"))' <<<"$identity_json")" || die 'STS returned an invalid account ID.'
caller_arn="$(jq -er '.Arn' <<<"$identity_json")" || die 'STS returned no caller ARN.'

if [[ -n "${EXPECTED_AWS_ACCOUNT_ID:-}" ]]; then
  [[ "$EXPECTED_AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || die 'EXPECTED_AWS_ACCOUNT_ID must be exactly 12 digits.'
  [[ "$actual_account_id" == "$EXPECTED_AWS_ACCOUNT_ID" ]] || die "AWS account mismatch: expected $EXPECTED_AWS_ACCOUNT_ID, got $actual_account_id."
elif [[ "$mode" == 'execute' ]]; then
  die 'EXPECTED_AWS_ACCOUNT_ID is required for --execute.'
fi

printf 'AWS identity: account=%s principal=%s region=%s\n' "$actual_account_id" "$caller_arn" "$aws_region_value"

verify_table() {
  local table_name="$1"
  local expected_gsi_count="$2"
  local expected_stream_view="$3"
  local description
  local pitr

  description="$("${aws_cli[@]}" dynamodb describe-table --table-name "$table_name" --output json)" || die "Cannot describe DynamoDB table $table_name."
  jq -e \
    --arg table "$table_name" \
    --argjson gsi_count "$expected_gsi_count" \
    --arg stream_view "$expected_stream_view" \
    '
      .Table.TableName == $table and
      .Table.TableStatus == "ACTIVE" and
      .Table.BillingModeSummary.BillingMode == "PAY_PER_REQUEST" and
      .Table.DeletionProtectionEnabled == true and
      .Table.SSEDescription.Status == "ENABLED" and
      .Table.StreamSpecification.StreamEnabled == true and
      .Table.StreamSpecification.StreamViewType == $stream_view and
      (.Table.LatestStreamArn | type == "string" and length > 0) and
      (.Table.KeySchema == [
        {AttributeName: "PK", KeyType: "HASH"},
        {AttributeName: "SK", KeyType: "RANGE"}
      ]) and
      ((.Table.GlobalSecondaryIndexes // []) | length == $gsi_count) and
      ((.Table.GlobalSecondaryIndexes // []) | map(.IndexName) | sort == [range(1; $gsi_count + 1) | "GSI\(.)"]) and
      ((.Table.GlobalSecondaryIndexes // []) | all(
        .KeySchema == [
          {AttributeName: (.IndexName + "PK"), KeyType: "HASH"},
          {AttributeName: (.IndexName + "SK"), KeyType: "RANGE"}
        ]
      )) and
      ((.Table.GlobalSecondaryIndexes // []) | all(.IndexStatus == "ACTIVE"))
    ' <<<"$description" >/dev/null || die "Table $table_name does not match the migration contract or is not fully active."

  pitr="$("${aws_cli[@]}" dynamodb describe-continuous-backups --table-name "$table_name" --output json)" || die "Cannot read PITR state for $table_name."
  jq -e '.ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus == "ENABLED"' \
    <<<"$pitr" >/dev/null || die "Point-in-time recovery is not enabled for $table_name."
}

verify_table "$main_table" 6 'NEW_AND_OLD_IMAGES'
verify_table "$ephemeral_table" 2 'NEW_IMAGE'

ttl_json="$("${aws_cli[@]}" dynamodb describe-time-to-live --table-name "$ephemeral_table" --output json)" || die "Cannot read TTL state for $ephemeral_table."
ttl_status="$(jq -er '.TimeToLiveDescription.TimeToLiveStatus' <<<"$ttl_json")" || die "TTL has no status on $ephemeral_table."
jq -e '.TimeToLiveDescription.AttributeName == "expiresAt"' <<<"$ttl_json" >/dev/null || die "TTL attribute on $ephemeral_table must be expiresAt."
if [[ "$mode" == 'execute' ]]; then
  [[ "$ttl_status" == 'ENABLED' ]] || die "TTL must be ENABLED before execution; current status is $ttl_status."
else
  [[ "$ttl_status" == 'ENABLED' || "$ttl_status" == 'ENABLING' ]] || die "TTL must be enabled; current status is $ttl_status."
fi

if [[ "${DB_IAM_AUTH_ENABLED:-false}" == 'true' ]]; then
  require_command aws
  [[ -n "${DB_IAM_AUTH_HOSTNAME:-}" ]] || die 'DB_IAM_AUTH_HOSTNAME is required when DB_IAM_AUTH_ENABLED=true.'
  db_iam_port="${DB_IAM_AUTH_PORT:-5432}"
  [[ "$db_iam_port" =~ ^[0-9]{1,5}$ ]] || die 'DB_IAM_AUTH_PORT must be numeric.'
  export DB_PASSWORD
  DB_PASSWORD="$("${aws_cli[@]}" rds generate-db-auth-token \
    --hostname "$DB_IAM_AUTH_HOSTNAME" \
    --port "$db_iam_port" \
    --username "$DB_USERNAME")" || die 'Unable to generate RDS IAM authentication token.'
elif [[ "$mode" == 'execute' && -z "${DB_PASSWORD:-}" ]]; then
  die 'DB_PASSWORD is required for --execute when RDS IAM authentication is disabled.'
fi

psql_url="${DB_URL#jdbc:}"
source_database_name="$(PGUSER="$DB_USERNAME" PGPASSWORD="${DB_PASSWORD:-}" \
  PGCONNECT_TIMEOUT=10 psql "$psql_url" -X -w -v ON_ERROR_STOP=1 -Atqc \
  'SELECT current_database();')" \
  || die 'PostgreSQL source preflight failed.'
[[ "$source_database_name" == "$EXPECTED_SOURCE_DATABASE_NAME" ]] \
  || die "PostgreSQL database mismatch: expected $EXPECTED_SOURCE_DATABASE_NAME, got $source_database_name."

source_tables_csv="$(PGUSER="$DB_USERNAME" PGPASSWORD="${DB_PASSWORD:-}" \
  PGCONNECT_TIMEOUT=10 psql "$psql_url" -X -w -v ON_ERROR_STOP=1 -Atqc \
  "SELECT COALESCE(string_agg(table_name, ',' ORDER BY table_name), '') FROM information_schema.tables WHERE table_schema = 'challengesphere' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history';")" \
  || die 'Unable to read the PostgreSQL source table manifest.'
[[ "$source_tables_csv" == "$EXPECTED_SOURCE_TABLES_CSV" ]] \
  || die "PostgreSQL source table manifest differs from migration V${EXPECTED_SOURCE_FLYWAY_VERSION}."

flyway_history_schema="$(PGUSER="$DB_USERNAME" PGPASSWORD="${DB_PASSWORD:-}" \
  PGCONNECT_TIMEOUT=10 psql "$psql_url" -X -w -v ON_ERROR_STOP=1 -Atqc \
  "SELECT CASE WHEN to_regclass('challengesphere.flyway_schema_history') IS NOT NULL THEN 'challengesphere' WHEN to_regclass('public.flyway_schema_history') IS NOT NULL THEN 'public' ELSE '' END;")" \
  || die 'Unable to locate Flyway source history.'
[[ "$flyway_history_schema" == 'challengesphere' || "$flyway_history_schema" == 'public' ]] \
  || die 'A successful Flyway schema history is required in public or challengesphere.'
source_flyway_version="$(PGUSER="$DB_USERNAME" PGPASSWORD="${DB_PASSWORD:-}" \
  PGCONNECT_TIMEOUT=10 psql "$psql_url" -X -w -v ON_ERROR_STOP=1 -Atqc \
  "SELECT version FROM ${flyway_history_schema}.flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1;")" \
  || die 'Unable to read the latest successful Flyway migration.'
[[ "$source_flyway_version" == "$EXPECTED_SOURCE_FLYWAY_VERSION" ]] \
  || die "PostgreSQL source Flyway version must be ${EXPECTED_SOURCE_FLYWAY_VERSION}; got ${source_flyway_version:-missing}."
failed_flyway_count="$(PGUSER="$DB_USERNAME" PGPASSWORD="${DB_PASSWORD:-}" \
  PGCONNECT_TIMEOUT=10 psql "$psql_url" -X -w -v ON_ERROR_STOP=1 -Atqc \
  "SELECT COUNT(*) FROM ${flyway_history_schema}.flyway_schema_history WHERE success = FALSE;")" \
  || die 'Unable to inspect failed Flyway migrations.'
[[ "$failed_flyway_count" == '0' ]] \
  || die "PostgreSQL source Flyway history contains ${failed_flyway_count} failed migration row(s)."
source_table_count="$(tr ',' '\n' <<<"$source_tables_csv" | wc -l | tr -d '[:space:]')"
printf 'Source preflight: database=%s schema=challengesphere flyway=%s base_tables=%s user=%s\n' \
  "$source_database_name" "$source_flyway_version" "$source_table_count" "$DB_USERNAME"

migration_run_id="${DYNAMODB_MIGRATION_RUN_ID:-}"
if [[ "$mode" == 'execute' ]]; then
  [[ "${SOURCE_WRITES_DISABLED:-false}" == 'true' ]] || die 'Set SOURCE_WRITES_DISABLED=true only after API and scheduled-job writes are disabled.'
  [[ "${FORCED_REAUTH_ACCEPTED:-false}" == 'true' ]] || die 'Set FORCED_REAUTH_ACCEPTED=true only after the cutover logout and user communication are approved.'
  [[ "$migration_run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$ ]] || die 'DYNAMODB_MIGRATION_RUN_ID must be a stable 3-128 character identifier.'

  expected_confirmation="MIGRATE:${actual_account_id}:${aws_region_value}:${main_table}:${migration_run_id}"
  if [[ -z "$confirmation" && -t 0 ]]; then
    printf 'Type this exact phrase to start/resume the idempotent migration:\n%s\n> ' "$expected_confirmation" >&2
    IFS= read -r confirmation
  fi
  [[ "$confirmation" == "$expected_confirmation" ]] || die 'Migration confirmation did not match. No migration was started.'
fi

export AWS_REGION="$aws_region_value"
export AWS_DEFAULT_REGION="$aws_region_value"
export DYNAMODB_MAIN_TABLE_NAME="$main_table"
export DYNAMODB_EPHEMERAL_TABLE_NAME="$ephemeral_table"
export DYNAMODB_MIGRATION_ENABLED=true
if [[ "$mode" == 'execute' ]]; then
  runner_mode='EXECUTE'
else
  runner_mode='PLAN'
fi
export DYNAMODB_MIGRATION_MODE="$runner_mode"
export DYNAMODB_MIGRATION_RUN_ID="$migration_run_id"
export SPRING_PROFILES_ACTIVE='dynamodb-migration'
if [[ -n "$aws_profile_value" ]]; then
  export AWS_PROFILE="$aws_profile_value"
  export AWS_SDK_LOAD_CONFIG=1
fi

printf 'Invoking migration runner: mode=%s main_table=%s ephemeral_table=%s run_id=%s\n' \
  "$runner_mode" "$main_table" "$ephemeral_table" "${migration_run_id:-not-required-for-plan}"

java -jar "$app_jar" \
  --spring.main.web-application-type=none \
  --spring.profiles.active=dynamodb-migration \
  --app.scheduling.enabled=false \
  --app.dynamodb.region="$aws_region_value" \
  --app.dynamodb.main-table="$main_table" \
  --app.dynamodb.ephemeral-table="$ephemeral_table" \
  --app.dynamodb.profile="$aws_profile_value" \
  --app.migration.run=true \
  --app.migration.mode="$mode" \
  --app.migration.source-schema=challengesphere \
  --app.migration.run-id="$migration_run_id"

if [[ "$mode" == 'execute' ]]; then
  printf 'Migration runner returned successfully. Run scripts/verify-dynamodb-cutover.sh --phase pre-cutover before changing application traffic.\n'
else
  printf 'Migration PLAN completed without authorizing writes.\n'
fi
