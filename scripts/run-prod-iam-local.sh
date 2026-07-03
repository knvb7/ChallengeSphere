#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RDS_HOST="${DB_IAM_AUTH_HOSTNAME:-challengesphere-prod-encrypted.cluster-cz2ywcai8jwm.ap-south-1.rds.amazonaws.com}"
RDS_PORT="${DB_IAM_AUTH_PORT:-5432}"
DB_NAME="${DB_NAME:-postgres}"
DB_USER="${DB_USERNAME:-postgres}"
AWS_REGION_VALUE="${AWS_REGION:-ap-south-1}"

export AWS_REGION="$AWS_REGION_VALUE"
export AWS_SDK_LOAD_CONFIG=1

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
export DB_IAM_AUTH_ENABLED="${DB_IAM_AUTH_ENABLED:-true}"
export DB_USERNAME="$DB_USER"
export DB_PASSWORD="${DB_PASSWORD:-}"
export DB_IAM_AUTH_HOSTNAME="$RDS_HOST"
export DB_IAM_AUTH_PORT="$RDS_PORT"
export DB_URL="${DB_URL:-jdbc:postgresql://$RDS_HOST:$RDS_PORT/$DB_NAME?sslmode=require}"

echo "Verifying AWS caller identity with the default credential chain..."
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
