#!/usr/bin/env bash

set -Eeuo pipefail

bucket_name="challengesphere-submissions-prod-ap-south-1"
object_prefix="backend-deployments/ios-release-20260820/migrations"
db_host="challengesphere-prod-encrypted.cluster-cz2ywcai8jwm.ap-south-1.rds.amazonaws.com"
db_port=5432
db_name="postgres"
db_user="postgres"
work_dir="$(mktemp -d /tmp/challengesphere-ios-migrations.XXXXXX)"

cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

python3 - "$bucket_name" "$object_prefix" "$work_dir" <<'PY'
import pathlib
import sys

import boto3

bucket, prefix, destination = sys.argv[1:]
s3 = boto3.client("s3", region_name="ap-south-1")
for filename in (
    "V48__add_auth_identities.sql",
    "V49__add_content_reports.sql",
    "V50__add_user_report_resolution.sql",
    "V51__add_apple_account_events.sql",
    "V53__minimize_deleted_user_identity_retention.sql",
):
    local_path = pathlib.Path(destination, filename)
    s3.download_file(bucket, f"{prefix}/{filename}", str(local_path))
PY

db_token="$(python3 - "$db_host" "$db_port" "$db_user" <<'PY'
import sys

import boto3

print(
    boto3.client("rds", region_name="ap-south-1").generate_db_auth_token(
        DBHostname=sys.argv[1],
        Port=int(sys.argv[2]),
        DBUsername=sys.argv[3],
    ),
    end="",
)
PY
)"

connection="host=$db_host port=$db_port dbname=$db_name user=$db_user sslmode=require connect_timeout=8"
PGPASSWORD="$db_token" psql "$connection" \
  --single-transaction \
  -v ON_ERROR_STOP=1 \
  -f "$work_dir/V48__add_auth_identities.sql" \
  -f "$work_dir/V49__add_content_reports.sql" \
  -f "$work_dir/V50__add_user_report_resolution.sql" \
  -f "$work_dir/V51__add_apple_account_events.sql" \
  -f "$work_dir/V53__minimize_deleted_user_identity_retention.sql"

PGPASSWORD="$db_token" psql "$connection" -v ON_ERROR_STOP=1 -Atc "
SELECT COALESCE(to_regclass('challengesphere.auth_identities')::text, 'missing'),
       COALESCE(to_regclass('challengesphere.provider_token_revocations')::text, 'missing'),
       COALESCE(to_regclass('challengesphere.content_reports')::text, 'missing'),
       COALESCE(to_regclass('challengesphere.apple_account_events')::text, 'missing');
"
