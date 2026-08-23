#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

phase='pre-cutover'
stack_name="${DYNAMODB_STACK_NAME:-}"
aws_region_value="${AWS_REGION:-${AWS_DEFAULT_REGION:-}}"
aws_profile_value="${AWS_PROFILE:-}"
main_table="${DYNAMODB_MAIN_TABLE_NAME:-challengesphere-main}"
ephemeral_table="${DYNAMODB_EPHEMERAL_TABLE_NAME:-challengesphere-ephemeral}"
migration_run_id="${DYNAMODB_MIGRATION_RUN_ID:-}"
confirmation=''
expected_mapping_version='2026-08-22-v3'

usage() {
  cat <<'USAGE'
Usage:
  scripts/verify-dynamodb-cutover.sh --phase PHASE --stack STACK [options]

This script is a read-only operational audit. It never changes traffic, writes
items, disables deletion protection, or deletes RDS/ElastiCache resources.

Phases:
  pre-cutover              Require a VERIFIED migration checkpoint and validate
                           the stack before an operator changes application traffic.
  post-cutover             Revalidate the data plane after traffic was changed.
  decommission-readiness   After the rollback window, validate retained final
                           snapshots. This still performs no decommissioning.

Options:
  --stack NAME             DynamoDB CloudFormation stack (or DYNAMODB_STACK_NAME).
  --region REGION          AWS region (or AWS_REGION/AWS_DEFAULT_REGION).
  --profile PROFILE        Optional AWS CLI/profile-chain profile.
  --main-table NAME        Durable table name.
  --ephemeral-table NAME   TTL table name.
  --run-id ID              Stable migration run ID.
  --confirm PHRASE         Non-interactive exact safety confirmation.
  --help                   Show this help.

Required environment for every phase:
  EXPECTED_AWS_ACCOUNT_ID  Exact 12-digit account ID; compared with STS.

Additional pre-cutover environment:
  SOURCE_WRITES_DISABLED=true
  FORCED_REAUTH_ACCEPTED=true

Additional post-cutover environment:
  APPLICATION_DYNAMODB_HEALTH_CONFIRMED=true
  CUTOVER_COMPLETED_AT_UTC=<ISO-8601 UTC timestamp>

Additional decommission-readiness environment:
  RDS_CLUSTER_IDENTIFIER
  FINAL_RDS_SNAPSHOT_ID
  ELASTICACHE_REPLICATION_GROUP_ID
  FINAL_ELASTICACHE_SNAPSHOT_NAME
  SOURCE_FREEZE_AT_UTC=<ISO-8601 UTC timestamp recorded after writes stopped>
  ROLLBACK_WINDOW_END_UTC=<ISO-8601 UTC timestamp>
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
    --phase)
      (($# >= 2)) || die '--phase requires a value'
      phase="$2"
      shift 2
      ;;
    --stack)
      (($# >= 2)) || die '--stack requires a value'
      stack_name="$2"
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
    --run-id)
      (($# >= 2)) || die '--run-id requires a value'
      migration_run_id="$2"
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

[[ "$phase" == 'pre-cutover' || "$phase" == 'post-cutover' || "$phase" == 'decommission-readiness' ]] \
  || die "Invalid phase: $phase"

for dependency in aws jq; do
  require_command "$dependency"
done

[[ -n "$stack_name" ]] || die 'Pass --stack or set DYNAMODB_STACK_NAME.'
[[ "$stack_name" =~ ^[A-Za-z][-A-Za-z0-9]{0,127}$ ]] || die "Invalid CloudFormation stack name: $stack_name"
[[ -n "$aws_region_value" ]] || die 'Set AWS_REGION/AWS_DEFAULT_REGION or pass --region.'
[[ "$aws_region_value" =~ ^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$ ]] || die "Invalid AWS region: $aws_region_value"
[[ "$main_table" =~ ^[A-Za-z0-9_.-]{3,255}$ ]] || die "Invalid main table name: $main_table"
[[ "$ephemeral_table" =~ ^[A-Za-z0-9_.-]{3,255}$ ]] || die "Invalid ephemeral table name: $ephemeral_table"
[[ "$migration_run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$ ]] || die 'Pass --run-id or set a valid DYNAMODB_MIGRATION_RUN_ID.'
[[ "${EXPECTED_AWS_ACCOUNT_ID:-}" =~ ^[0-9]{12}$ ]] || die 'EXPECTED_AWS_ACCOUNT_ID must be exactly 12 digits.'

aws_cli=(aws)
if [[ -n "$aws_profile_value" ]]; then
  aws_cli+=(--profile "$aws_profile_value")
fi
aws_cli+=(--region "$aws_region_value")

identity_json="$("${aws_cli[@]}" sts get-caller-identity --output json)" || die 'Unable to resolve AWS caller identity.'
actual_account_id="$(jq -er '.Account | select(test("^[0-9]{12}$"))' <<<"$identity_json")" || die 'STS returned an invalid account ID.'
caller_arn="$(jq -er '.Arn' <<<"$identity_json")" || die 'STS returned no caller ARN.'
[[ "$actual_account_id" == "$EXPECTED_AWS_ACCOUNT_ID" ]] \
  || die "AWS account mismatch: expected $EXPECTED_AWS_ACCOUNT_ID, got $actual_account_id."
printf 'Audit identity: account=%s principal=%s region=%s phase=%s\n' \
  "$actual_account_id" "$caller_arn" "$aws_region_value" "$phase"

stack_json="$("${aws_cli[@]}" cloudformation describe-stacks --stack-name "$stack_name" --output json)" \
  || die "Cannot describe CloudFormation stack $stack_name."
jq -e '
  .Stacks | length == 1 and
  (.[0].StackStatus | test("^(CREATE|UPDATE|IMPORT)_COMPLETE$")) and
  (.[0].EnableTerminationProtection == true)
' <<<"$stack_json" >/dev/null || die 'The DynamoDB stack must be complete and have CloudFormation termination protection enabled.'

stack_output() {
  local key="$1"
  jq -er --arg key "$key" '.Stacks[0].Outputs[] | select(.OutputKey == $key) | .OutputValue' <<<"$stack_json"
}

stack_parameter() {
  local key="$1"
  jq -er --arg key "$key" '.Stacks[0].Parameters[] | select(.ParameterKey == $key) | .ParameterValue' <<<"$stack_json"
}

[[ "$(stack_output MainTableName)" == "$main_table" ]] || die 'Main table does not match the stack output.'
[[ "$(stack_output EphemeralTableName)" == "$ephemeral_table" ]] || die 'Ephemeral table does not match the stack output.'
main_table_arn="$(stack_output MainTableArn)" || die 'MainTableArn stack output is missing.'
ephemeral_table_arn="$(stack_output EphemeralTableArn)" || die 'EphemeralTableArn stack output is missing.'
runtime_policy_arn="$(stack_output RuntimeManagedPolicyArn)" || die 'RuntimeManagedPolicyArn stack output is missing.'
runtime_role_arn="$(stack_output RuntimeRoleArn)" || die 'RuntimeRoleArn stack output is missing.'
backup_vault_name="$(stack_output BackupVaultName)" || die 'BackupVaultName stack output is missing; scheduled backups are required for cutover.'
backup_plan_id="$(stack_output BackupPlanId)" || die 'BackupPlanId stack output is missing; scheduled backups are required for cutover.'
[[ "$(stack_parameter EnableScheduledBackups)" == 'true' ]] \
  || die 'EnableScheduledBackups must be true in the deployed stack.'
backup_retention_days="$(stack_parameter BackupRetentionDays)" \
  || die 'BackupRetentionDays stack parameter is missing.'
[[ "$backup_retention_days" =~ ^[0-9]+$ ]] \
  || die 'BackupRetentionDays must be numeric.'

verify_table() {
  local table_name="$1"
  local expected_gsi_count="$2"
  local expected_stream_view="$3"
  local expected_pitr_days="$4"
  local table_json
  local pitr_json

  table_json="$("${aws_cli[@]}" dynamodb describe-table --table-name "$table_name" --output json)" \
    || die "Cannot describe DynamoDB table $table_name."
  jq -e \
    --arg name "$table_name" \
    --argjson gsi_count "$expected_gsi_count" \
    --arg stream_view "$expected_stream_view" \
    '
      .Table.TableName == $name and
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
    ' <<<"$table_json" >/dev/null || die "Table contract check failed for $table_name."

  pitr_json="$("${aws_cli[@]}" dynamodb describe-continuous-backups --table-name "$table_name" --output json)" \
    || die "Cannot read PITR state for $table_name."
  jq -e --argjson days "$expected_pitr_days" '
    .ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus == "ENABLED" and
    .ContinuousBackupsDescription.PointInTimeRecoveryDescription.RecoveryPeriodInDays == $days
  ' \
    <<<"$pitr_json" >/dev/null || die "PITR is not enabled with the exact 35-day recovery period for $table_name."
}

verify_table "$main_table" 6 'NEW_AND_OLD_IMAGES' 35
verify_table "$ephemeral_table" 2 'NEW_IMAGE' 35

ttl_json="$("${aws_cli[@]}" dynamodb describe-time-to-live --table-name "$ephemeral_table" --output json)" \
  || die "Cannot read TTL state for $ephemeral_table."
jq -e '
  .TimeToLiveDescription.TimeToLiveStatus == "ENABLED" and
  .TimeToLiveDescription.AttributeName == "expiresAt"
' <<<"$ttl_json" >/dev/null || die 'Ephemeral TTL must be ENABLED on numeric expiresAt values before cutover.'

policy_json="$("${aws_cli[@]}" iam get-policy --policy-arn "$runtime_policy_arn" --output json)" \
  || die 'Cannot read the runtime DynamoDB managed policy.'
policy_version_id="$(jq -er '.Policy.DefaultVersionId' <<<"$policy_json")"
policy_version_json="$("${aws_cli[@]}" iam get-policy-version \
  --policy-arn "$runtime_policy_arn" \
  --version-id "$policy_version_id" \
  --output json)" || die 'Cannot read the default runtime policy version.'
jq -e \
  --arg main "$main_table_arn" \
  --arg main_indexes "${main_table_arn}/index/*" \
  --arg ephemeral "$ephemeral_table_arn" \
  --arg ephemeral_indexes "${ephemeral_table_arn}/index/*" \
  '
  [.PolicyVersion.Document.Statement[] | select(.Effect == "Allow") | .Action | if type == "array" then .[] else . end] as $actions |
  [.PolicyVersion.Document.Statement[] | select(.Effect == "Allow") | .Resource | if type == "array" then .[] else . end] as $resources |
  (($actions | unique | sort) == ([
    "dynamodb:BatchGetItem",
    "dynamodb:BatchWriteItem",
    "dynamodb:DeleteItem",
    "dynamodb:DescribeTable",
    "dynamodb:GetItem",
    "dynamodb:PutItem",
    "dynamodb:Query",
    "dynamodb:TransactGetItems",
    "dynamodb:TransactWriteItems",
    "dynamodb:UpdateItem"
  ] | sort)) and
  (($resources | unique | sort) == ([
    $main,
    $main_indexes,
    $ephemeral,
    $ephemeral_indexes
  ] | sort))
' <<<"$policy_version_json" >/dev/null || die 'Runtime policy is missing required operations or permits Scan/administrative/PartiQL access.'

[[ "$runtime_role_arn" != 'UNATTACHED' ]] \
  || die 'The production backend runtime policy must be attached to a concrete IAM role.'

simulate_runtime_target() {
  local resource_arn="$1"
  local require_data_access="$2"
  local simulation_json
  simulation_json="$("${aws_cli[@]}" iam simulate-principal-policy \
    --policy-source-arn "$runtime_role_arn" \
    --action-names \
      dynamodb:GetItem \
      dynamodb:PutItem \
      dynamodb:Query \
      dynamodb:Scan \
      dynamodb:DeleteTable \
      dynamodb:UpdateTable \
      dynamodb:UpdateContinuousBackups \
      dynamodb:RestoreTableFromBackup \
      dynamodb:ExportTableToPointInTime \
      dynamodb:ExecuteStatement \
    --resource-arns "$resource_arn" \
    --output json)" || die 'Cannot simulate the effective backend runtime role policy.'
  jq -e --argjson require_data_access "$require_data_access" '
    [.EvaluationResults[] | {key: .EvalActionName, value: .EvalDecision}] | from_entries as $results |
    (if $require_data_access then
      $results["dynamodb:GetItem"] == "allowed" and
      $results["dynamodb:PutItem"] == "allowed" and
      $results["dynamodb:Query"] == "allowed"
    else
      $results["dynamodb:GetItem"] != "allowed" and
      $results["dynamodb:PutItem"] != "allowed" and
      $results["dynamodb:Query"] != "allowed"
    end) and
    $results["dynamodb:Scan"] != "allowed" and
    $results["dynamodb:DeleteTable"] != "allowed" and
    $results["dynamodb:UpdateTable"] != "allowed" and
    $results["dynamodb:UpdateContinuousBackups"] != "allowed" and
    $results["dynamodb:RestoreTableFromBackup"] != "allowed" and
    $results["dynamodb:ExportTableToPointInTime"] != "allowed" and
    $results["dynamodb:ExecuteStatement"] != "allowed"
  ' <<<"$simulation_json" >/dev/null \
    || die "The effective backend role has incorrect access for $resource_arn."
}

simulate_runtime_target "$main_table_arn" true
simulate_runtime_target "$ephemeral_table_arn" true

index_arns=()
for index_number in 1 2 3 4 5 6; do
  index_arns+=("${main_table_arn}/index/GSI${index_number}")
done
for index_number in 1 2; do
  index_arns+=("${ephemeral_table_arn}/index/GSI${index_number}")
done
index_simulation_json="$("${aws_cli[@]}" iam simulate-principal-policy \
  --policy-source-arn "$runtime_role_arn" \
  --action-names dynamodb:Query dynamodb:Scan \
  --resource-arns "${index_arns[@]}" \
  --output json)" || die 'Cannot simulate the effective backend role against every DynamoDB index.'
jq -e --argjson expected "${#index_arns[@]}" '
  ([.EvaluationResults[] | select(.EvalActionName == "dynamodb:Query")] | length) == $expected and
  ([.EvaluationResults[] | select(.EvalActionName == "dynamodb:Query")] | all(.EvalDecision == "allowed")) and
  ([.EvaluationResults[] | select(.EvalActionName == "dynamodb:Scan")] | length) == $expected and
  ([.EvaluationResults[] | select(.EvalActionName == "dynamodb:Scan")] | all(.EvalDecision != "allowed"))
' <<<"$index_simulation_json" >/dev/null \
  || die 'The effective backend role must allow Query and deny Scan on every approved DynamoDB index.'

aws_partition="$(cut -d: -f2 <<<"$main_table_arn")"
unrelated_table_arn="arn:${aws_partition}:dynamodb:${aws_region_value}:${actual_account_id}:table/challengesphere-unapproved-probe"
simulate_runtime_target "$unrelated_table_arn" false
unrelated_index_arn="${unrelated_table_arn}/index/unapproved"
unrelated_index_simulation_json="$("${aws_cli[@]}" iam simulate-principal-policy \
  --policy-source-arn "$runtime_role_arn" \
  --action-names dynamodb:Query dynamodb:Scan \
  --resource-arns "$unrelated_index_arn" \
  --output json)" || die 'Cannot simulate the effective backend role against an unrelated DynamoDB index.'
jq -e '
  (.EvaluationResults | length) == 2 and
  (.EvaluationResults | all(.EvalDecision != "allowed"))
' <<<"$unrelated_index_simulation_json" >/dev/null \
  || die 'The effective backend role permits data access to an unrelated DynamoDB index.'

"${aws_cli[@]}" backup describe-backup-vault --backup-vault-name "$backup_vault_name" --output json >/dev/null \
  || die "Cannot describe AWS Backup vault $backup_vault_name."
backup_plan_json="$("${aws_cli[@]}" backup get-backup-plan --backup-plan-id "$backup_plan_id" --output json)" \
  || die "Cannot describe AWS Backup plan $backup_plan_id."
jq -e \
  --arg vault "$backup_vault_name" \
  --argjson retention "$backup_retention_days" \
  '
    .BackupPlan.Rules | length == 1 and
    .[0].RuleName == "daily-retained-backup" and
    .[0].TargetBackupVaultName == $vault and
    .[0].ScheduleExpression == "cron(30 1 * * ? *)" and
    .[0].ScheduleExpressionTimezone == "Asia/Kolkata" and
    .[0].StartWindowMinutes == 60 and
    .[0].CompletionWindowMinutes == 360 and
    .[0].Lifecycle.DeleteAfterDays == $retention
  ' <<<"$backup_plan_json" >/dev/null \
  || die 'AWS Backup plan does not exactly match the stack retention, vault, schedule, timezone, or windows.'

backup_selections_json="$("${aws_cli[@]}" backup list-backup-selections \
  --backup-plan-id "$backup_plan_id" \
  --output json)" || die 'Cannot list AWS Backup selections.'
backup_selection_id="$(jq -er '
  [.BackupSelectionsList[] | select(.SelectionName == "exact-dynamodb-tables")] |
  select(length == 1) | .[0].SelectionId
' <<<"$backup_selections_json")" || die 'The exact DynamoDB backup selection is missing or ambiguous.'
backup_selection_json="$("${aws_cli[@]}" backup get-backup-selection \
  --backup-plan-id "$backup_plan_id" \
  --selection-id "$backup_selection_id" \
  --output json)" || die 'Cannot read the exact DynamoDB backup selection.'
jq -e \
  --arg main "$main_table_arn" \
  --arg ephemeral "$ephemeral_table_arn" \
  '(.BackupSelection.Resources | sort) == ([$main, $ephemeral] | sort)' \
  <<<"$backup_selection_json" >/dev/null \
  || die 'AWS Backup selection must contain exactly the main and ephemeral tables.'

stack_resources_json="$("${aws_cli[@]}" cloudformation list-stack-resources --stack-name "$stack_name" --output json)" \
  || die 'Cannot enumerate stack resources.'
alarm_names=()
while IFS= read -r alarm_name; do
  [[ -n "$alarm_name" ]] && alarm_names+=("$alarm_name")
done < <(jq -r '.StackResourceSummaries[] | select(.ResourceType == "AWS::CloudWatch::Alarm") | .PhysicalResourceId' <<<"$stack_resources_json")
((${#alarm_names[@]} == 13)) || die 'Expected exactly 13 baseline DynamoDB/backup CloudWatch alarms in the stack.'
alarm_json="$("${aws_cli[@]}" cloudwatch describe-alarms --alarm-names "${alarm_names[@]}" --output json)" \
  || die 'Cannot describe CloudWatch alarms.'
jq -e --argjson expected_count "${#alarm_names[@]}" '
  (.MetricAlarms | length) == $expected_count and
  (.MetricAlarms | all(.StateValue != "ALARM"))
' <<<"$alarm_json" >/dev/null \
  || die 'At least one expected DynamoDB/backup alarm is missing or currently in ALARM.'

checkpoint_key="$(jq -cn \
  --arg pk "MIGRATION#$migration_run_id" \
  '{PK: {S: $pk}, SK: {S: "CHECKPOINT#SUMMARY"}}')"
checkpoint_json="$("${aws_cli[@]}" dynamodb get-item \
  --table-name "$main_table" \
  --consistent-read \
  --key "$checkpoint_key" \
  --output json)" || die 'Cannot read the migration summary checkpoint.'
jq -e --arg mapping_version "$expected_mapping_version" '
  .Item.status.S == "VERIFIED" and
  (.Item.failedItemCount.N | tonumber) == 0 and
  (.Item.sourceRowCount.N | tonumber) >= 0 and
  (.Item.canonicalItemCount.N | tonumber) >= 0 and
  (.Item.projectionItemCount.N | tonumber) >= 0 and
  (.Item.domainSourceRowCount.N | tonumber) >= 0 and
  (.Item.appleAccountEventSourceCount.N | tonumber) >= 0 and
  (.Item.accountDeletionRetentionSourceCount.N | tonumber) >= 0 and
  (.Item.terminalAppleAccountEventCount.N | tonumber) ==
    (.Item.appleAccountEventSourceCount.N | tonumber) and
  (.Item.verifiedAccountDeletionRetentionCount.N | tonumber) ==
    (.Item.accountDeletionRetentionSourceCount.N | tonumber) and
  ((.Item.domainSourceRowCount.N | tonumber) +
    (.Item.appleAccountEventSourceCount.N | tonumber) +
    (.Item.accountDeletionRetentionSourceCount.N | tonumber)) ==
    (.Item.sourceRowCount.N | tonumber) and
  (.Item.canonicalItemCount.N | tonumber) ==
    (.Item.sourceRowCount.N | tonumber) and
  .Item.feedTimelineReady.BOOL == true and
  (.Item.pendingSubmissionFanoutCount.N | tonumber) == 0 and
  (.Item.pendingFollowBackfillCount.N | tonumber) == 0 and
  (.Item.submissionFanoutChunkCount.N | tonumber) >= 0 and
  (.Item.followBackfillChunkCount.N | tonumber) >= 0 and
  .Item.challengeParticipantsReady.BOOL == true and
  (.Item.verifiedChallengeParticipantCounterCount.N | tonumber) >= 0 and
  (.Item.verifiedActiveParticipantProjectionCount.N | tonumber) >= 0 and
  (.Item.completedAt.S | length > 0) and
  (.Item.sourceSchemaFingerprint.S | length > 0) and
  .Item.targetMappingVersion.S == $mapping_version and
  .Item.authSessionPolicy.S == "FORCED_REAUTH"
' <<<"$checkpoint_json" >/dev/null || die 'Migration checkpoint is missing, not VERIFIED, or reports failed items.'

checkpoint_summary="$(jq -c '{
  runId: (.Item.PK.S | sub("^MIGRATION#"; "")),
  status: .Item.status.S,
  sourceRows: (.Item.sourceRowCount.N | tonumber),
  domainSourceRows: (.Item.domainSourceRowCount.N | tonumber),
  appleAccountEventSourceRows: (.Item.appleAccountEventSourceCount.N | tonumber),
  terminalAppleAccountEvents: (.Item.terminalAppleAccountEventCount.N | tonumber),
  accountDeletionRetentionSourceRows: (.Item.accountDeletionRetentionSourceCount.N | tonumber),
  verifiedAccountDeletionRetentionRows: (.Item.verifiedAccountDeletionRetentionCount.N | tonumber),
  canonicalItems: (.Item.canonicalItemCount.N | tonumber),
  projectionItems: (.Item.projectionItemCount.N | tonumber),
  feedTimelineReady: .Item.feedTimelineReady.BOOL,
  pendingSubmissionFanouts: (.Item.pendingSubmissionFanoutCount.N | tonumber),
  pendingFollowBackfills: (.Item.pendingFollowBackfillCount.N | tonumber),
  submissionFanoutChunks: (.Item.submissionFanoutChunkCount.N | tonumber),
  followBackfillChunks: (.Item.followBackfillChunkCount.N | tonumber),
  challengeParticipantsReady: .Item.challengeParticipantsReady.BOOL,
  verifiedChallengeParticipantCounters: (.Item.verifiedChallengeParticipantCounterCount.N | tonumber),
  verifiedActiveParticipantProjections: (.Item.verifiedActiveParticipantProjectionCount.N | tonumber),
  completedAt: .Item.completedAt.S,
  sourceSchemaFingerprint: .Item.sourceSchemaFingerprint.S,
  targetMappingVersion: .Item.targetMappingVersion.S
}' <<<"$checkpoint_json")"

if [[ "$phase" == 'pre-cutover' ]]; then
  [[ "${SOURCE_WRITES_DISABLED:-false}" == 'true' ]] \
    || die 'Set SOURCE_WRITES_DISABLED=true only after API and scheduled-job writes are disabled.'
  [[ "${FORCED_REAUTH_ACCEPTED:-false}" == 'true' ]] \
    || die 'Set FORCED_REAUTH_ACCEPTED=true only after the planned cutover logout and user communication are approved.'
  ephemeral_probe_json="$("${aws_cli[@]}" dynamodb scan \
    --table-name "$ephemeral_table" \
    --consistent-read \
    --limit 1 \
    --projection-expression '#pk' \
    --expression-attribute-names '{"#pk":"PK"}' \
    --output json)" || die 'Cannot prove that the cutover ephemeral table is fresh and empty.'
  jq -e '
    .Count == 0 and .ScannedCount == 0 and
    ((.Items // []) | length == 0) and
    ((.LastEvaluatedKey // {}) | length == 0)
  ' <<<"$ephemeral_probe_json" >/dev/null \
    || die 'The ephemeral table is not empty; use a fresh table or purge it through an explicitly reviewed operation before forced reauthentication.'
  expected_confirmation="CUTOVER-READINESS-ONLY:${actual_account_id}:${aws_region_value}:${migration_run_id}"
  if [[ -z "$confirmation" && -t 0 ]]; then
    printf 'This audit does not change traffic. Type the exact phrase to issue a readiness result:\n%s\n> ' \
      "$expected_confirmation" >&2
    IFS= read -r confirmation
  fi
  [[ "$confirmation" == "$expected_confirmation" ]] || die 'Cutover-readiness confirmation did not match.'
elif [[ "$phase" == 'post-cutover' ]]; then
  [[ "${APPLICATION_DYNAMODB_HEALTH_CONFIRMED:-false}" == 'true' ]] \
    || die 'Set APPLICATION_DYNAMODB_HEALTH_CONFIRMED=true only after the deployed API health and critical manual flows are confirmed.'
  [[ "${CUTOVER_COMPLETED_AT_UTC:-}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
    || die 'CUTOVER_COMPLETED_AT_UTC must be an ISO-8601 UTC timestamp such as 2026-08-22T12:30:00Z.'
else
  for required_value in RDS_CLUSTER_IDENTIFIER FINAL_RDS_SNAPSHOT_ID ELASTICACHE_REPLICATION_GROUP_ID FINAL_ELASTICACHE_SNAPSHOT_NAME SOURCE_FREEZE_AT_UTC ROLLBACK_WINDOW_END_UTC; do
    [[ -n "${!required_value:-}" ]] || die "$required_value is required for decommission-readiness."
  done
  [[ "$ROLLBACK_WINDOW_END_UTC" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
    || die 'ROLLBACK_WINDOW_END_UTC must be an ISO-8601 UTC timestamp.'
  [[ "$SOURCE_FREEZE_AT_UTC" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
    || die 'SOURCE_FREEZE_AT_UTC must be an ISO-8601 UTC timestamp.'

  if date -u -d "$ROLLBACK_WINDOW_END_UTC" +%s >/dev/null 2>&1; then
    rollback_end_epoch="$(date -u -d "$ROLLBACK_WINDOW_END_UTC" +%s)"
  elif date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$ROLLBACK_WINDOW_END_UTC" +%s >/dev/null 2>&1; then
    rollback_end_epoch="$(date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$ROLLBACK_WINDOW_END_UTC" +%s)"
  else
    die 'Unable to parse ROLLBACK_WINDOW_END_UTC with the local date command.'
  fi
  (( $(date -u +%s) >= rollback_end_epoch )) || die 'The rollback window has not ended.'

  rds_cluster_json="$("${aws_cli[@]}" rds describe-db-clusters \
    --db-cluster-identifier "$RDS_CLUSTER_IDENTIFIER" \
    --output json)" || die 'The source RDS cluster is absent; stop and investigate before any decommission decision.'
  jq -e --arg cluster "$RDS_CLUSTER_IDENTIFIER" '
    .DBClusters | length == 1 and
    .[0].DBClusterIdentifier == $cluster and
    .[0].Status == "available" and
    .[0].StorageEncrypted == true and
    ((.[0].KmsKeyId // "") | length > 0)
  ' <<<"$rds_cluster_json" >/dev/null \
    || die 'The source RDS cluster identity, state, or encryption metadata is not decommission-ready.'

  rds_snapshot_json="$("${aws_cli[@]}" rds describe-db-cluster-snapshots \
    --db-cluster-snapshot-identifier "$FINAL_RDS_SNAPSHOT_ID" \
    --output json)" || die 'Cannot describe the final RDS cluster snapshot.'
  jq -e \
    --arg cluster "$RDS_CLUSTER_IDENTIFIER" \
    --arg snapshot "$FINAL_RDS_SNAPSHOT_ID" \
    --arg freeze "$SOURCE_FREEZE_AT_UTC" \
    --arg expected_engine "$(jq -er '.DBClusters[0].Engine' <<<"$rds_cluster_json")" \
    --arg expected_kms "$(jq -er '.DBClusters[0].KmsKeyId' <<<"$rds_cluster_json")" \
    '
      def epoch: sub("\\.[0-9]+Z$"; "Z") | sub("\\.[0-9]+\\+00:00$"; "Z") | sub("\\+00:00$"; "Z") | fromdateiso8601;
      .DBClusterSnapshots | length == 1 and
      .[0].DBClusterIdentifier == $cluster and
      .[0].DBClusterSnapshotIdentifier == $snapshot and
      .[0].Status == "available" and
      .[0].Encrypted == true and
      .[0].SnapshotType == "manual" and
      .[0].Engine == $expected_engine and
      .[0].KmsKeyId == $expected_kms and
      ((.[0].SnapshotCreateTime | epoch) >= ($freeze | epoch))
    ' <<<"$rds_snapshot_json" >/dev/null \
    || die 'Final RDS snapshot does not match the frozen source, KMS key, engine, or source-freeze time.'

  cache_group_json="$("${aws_cli[@]}" elasticache describe-replication-groups \
    --replication-group-id "$ELASTICACHE_REPLICATION_GROUP_ID" \
    --output json)" || die 'The source ElastiCache replication group is absent; stop and investigate.'
  cache_cluster_id="$(jq -er \
    --arg group "$ELASTICACHE_REPLICATION_GROUP_ID" \
    '.ReplicationGroups | select(length == 1) | .[0] | select(.ReplicationGroupId == $group and .Status == "available" and .AtRestEncryptionEnabled == true) | .MemberClusters[0]' \
    <<<"$cache_group_json")" || die 'The source ElastiCache replication group identity, state, or at-rest encryption is invalid.'
  cache_cluster_json="$("${aws_cli[@]}" elasticache describe-cache-clusters \
    --cache-cluster-id "$cache_cluster_id" \
    --output json)" || die 'Cannot describe the source ElastiCache member cluster.'

  cache_snapshot_json="$("${aws_cli[@]}" elasticache describe-snapshots \
    --snapshot-name "$FINAL_ELASTICACHE_SNAPSHOT_NAME" \
    --output json)" || die 'Cannot describe the final ElastiCache snapshot.'
  jq -e \
    --arg group "$ELASTICACHE_REPLICATION_GROUP_ID" \
    --arg snapshot "$FINAL_ELASTICACHE_SNAPSHOT_NAME" \
    --arg freeze "$SOURCE_FREEZE_AT_UTC" \
    --arg expected_engine "$(jq -er '.CacheClusters[0].Engine' <<<"$cache_cluster_json")" \
    --arg expected_engine_version "$(jq -er '.CacheClusters[0].EngineVersion' <<<"$cache_cluster_json")" \
    --arg expected_node_type "$(jq -er '.CacheClusters[0].CacheNodeType' <<<"$cache_cluster_json")" \
    --arg expected_kms "$(jq -r '.ReplicationGroups[0].KmsKeyId // ""' <<<"$cache_group_json")" \
    '
      def epoch: sub("\\.[0-9]+Z$"; "Z") | sub("\\.[0-9]+\\+00:00$"; "Z") | sub("\\+00:00$"; "Z") | fromdateiso8601;
      .Snapshots | length == 1 and
      .[0].ReplicationGroupId == $group and
      .[0].SnapshotName == $snapshot and
      .[0].SnapshotStatus == "available" and
      .[0].SnapshotSource == "manual" and
      .[0].Engine == $expected_engine and
      .[0].EngineVersion == $expected_engine_version and
      .[0].CacheNodeType == $expected_node_type and
      ((.[0].KmsKeyId // "") == $expected_kms) and
      ((.[0].NodeSnapshots // []) | length > 0) and
      ((.[0].NodeSnapshots // []) | all((.SnapshotCreateTime | epoch) >= ($freeze | epoch)))
    ' <<<"$cache_snapshot_json" >/dev/null \
    || die 'Final ElastiCache snapshot does not match the frozen source, encryption, engine, node type, or source-freeze time.'

  expected_confirmation="DECOMMISSION-READINESS-ONLY:${actual_account_id}:${RDS_CLUSTER_IDENTIFIER}:${ELASTICACHE_REPLICATION_GROUP_ID}"
  if [[ -z "$confirmation" && -t 0 ]]; then
    printf 'This audit never deletes resources. Type the exact phrase to issue a readiness result:\n%s\n> ' \
      "$expected_confirmation" >&2
    IFS= read -r confirmation
  fi
  [[ "$confirmation" == "$expected_confirmation" ]] || die 'Decommission-readiness confirmation did not match.'
fi

printf '{"result":"READY","phase":"%s","account":"%s","region":"%s","stack":"%s","mainTable":"%s","ephemeralTable":"%s","migration":%s}\n' \
  "$phase" "$actual_account_id" "$aws_region_value" "$stack_name" "$main_table" "$ephemeral_table" "$checkpoint_summary"
printf 'READ-ONLY RESULT: no traffic, table, RDS, ElastiCache, backup, IAM, or alarm state was changed.\n'
