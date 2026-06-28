#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACCESS_KEYS_CSV="${ACCESS_KEYS_CSV:-$ROOT_DIR/challengesphere_accessKeys.csv}"
RDS_HOST="${DB_IAM_AUTH_HOSTNAME:-database-1.cluster-cz2ywcai8jwm.ap-south-1.rds.amazonaws.com}"
RDS_PORT="${DB_IAM_AUTH_PORT:-5432}"
DB_NAME="${DB_NAME:-postgres}"
DB_USER="${DB_USERNAME:-postgres}"
AWS_REGION_VALUE="${AWS_REGION:-ap-south-1}"

if [[ ! -f "$ACCESS_KEYS_CSV" ]]; then
  echo "Missing access keys CSV: $ACCESS_KEYS_CSV" >&2
  exit 1
fi

credential_line="$(tail -n +2 "$ACCESS_KEYS_CSV" | head -n 1 | tr -d '\r')"
IFS=',' read -r csv_access_key_id csv_secret_access_key _ <<< "$credential_line"

if [[ -z "${csv_access_key_id:-}" || -z "${csv_secret_access_key:-}" ]]; then
  echo "Access keys CSV must contain Access key ID and Secret access key columns." >&2
  exit 1
fi

export AWS_ACCESS_KEY_ID="$csv_access_key_id"
export AWS_SECRET_ACCESS_KEY="$csv_secret_access_key"
unset AWS_SESSION_TOKEN AWS_SECURITY_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE
export AWS_REGION="$AWS_REGION_VALUE"
export AWS_SDK_LOAD_CONFIG=1

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
export DB_IAM_AUTH_ENABLED="${DB_IAM_AUTH_ENABLED:-true}"
export DB_USERNAME="$DB_USER"
export DB_PASSWORD="${DB_PASSWORD:-}"
export DB_IAM_AUTH_HOSTNAME="$RDS_HOST"
export DB_IAM_AUTH_PORT="$RDS_PORT"
export DB_URL="${DB_URL:-jdbc:postgresql://$RDS_HOST:$RDS_PORT/$DB_NAME?sslmode=require}"

echo "Verifying AWS caller identity with CSV credentials..."
aws sts get-caller-identity --region "$AWS_REGION" --query '{Account:Account,Arn:Arn}' --output table

if [[ "${1:-}" == "--check-db" ]]; then
  if ! command -v psql >/dev/null 2>&1; then
    echo "psql is required for --check-db but was not found on PATH." >&2
    exit 1
  fi

  echo "Testing direct RDS IAM login for DB user '$DB_USER'..."
  token="$(aws rds generate-db-auth-token \
    --hostname "$RDS_HOST" \
    --port "$RDS_PORT" \
    --region "$AWS_REGION" \
    --username "$DB_USER")"
  PGPASSWORD="$token" psql \
    "host=$RDS_HOST port=$RDS_PORT dbname=$DB_NAME user=$DB_USER sslmode=require connect_timeout=8" \
    -c "select current_database(), current_user, current_schema();"
  exit 0
fi

echo "Starting Spring Boot with RDS IAM auth against $RDS_HOST:$RDS_PORT/$DB_NAME as $DB_USER..."
cd "$ROOT_DIR"
exec ./mvnw spring-boot:run
