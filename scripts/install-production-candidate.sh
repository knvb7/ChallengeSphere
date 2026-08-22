#!/usr/bin/env bash

set -Eeuo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <s3-candidate-uri> <expected-sha256>" >&2
  exit 64
fi

candidate_s3_uri="$1"
expected_sha256="$2"
service_name="challengesphere.service"
jar_path="/opt/challengesphere/challengesphere.jar"
env_path="/etc/challengesphere/challengesphere.env"
candidate_path="${jar_path}.candidate"
backup_suffix="$(date -u +%Y%m%dT%H%M%SZ)"
jar_backup="${jar_path}.rollback-${backup_suffix}"
env_backup="${env_path}.rollback-${backup_suffix}"
env_candidate="$(mktemp /etc/challengesphere/challengesphere.env.candidate.XXXXXX)"
rollback_required=false

rollback() {
  local exit_code=$?
  trap - ERR
  if [[ "$rollback_required" == true ]]; then
    echo "Candidate failed; restoring the previous production JAR and environment." >&2
    install -o ubuntu -g ubuntu -m 0644 "$jar_backup" "$jar_path"
    install -o root -g ubuntu -m 0640 "$env_backup" "$env_path"
    systemctl restart "$service_name"
  fi
  rm -f "$candidate_path" "$env_candidate"
  exit "$exit_code"
}
trap rollback ERR

systemctl is-active --quiet "$service_name"
curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null

python3 - "$candidate_s3_uri" "$candidate_path" <<'PY'
import sys
from urllib.parse import urlparse

import boto3

source = urlparse(sys.argv[1])
if source.scheme != "s3" or not source.netloc or not source.path.lstrip("/"):
    raise SystemExit("Candidate must be a complete s3:// URI")
boto3.client("s3", region_name="ap-south-1").download_file(
    source.netloc,
    source.path.lstrip("/"),
    sys.argv[2],
)
PY
printf '%s  %s\n' "$expected_sha256" "$candidate_path" | sha256sum --check --status

cp --preserve=mode,ownership,timestamps "$jar_path" "$jar_backup"
cp --preserve=mode,ownership,timestamps "$env_path" "$env_backup"
rollback_required=true

awk '
  !/^(APPLE_SIGNIN_ENABLED|APPLE_SIGNIN_CLIENT_IDS|APPLE_TEAM_ID|APPLE_KEY_ID|APPLE_PRIVATE_KEY_BASE64|APPLE_TOKEN_ENCRYPTION_KEY_BASE64|ACCOUNT_DELETION_IDENTITY_HASH_KEY|REVENUECAT_ENABLED|REVENUECAT_SECRET_API_KEY|REVENUECAT_ENTITLEMENT_ID|REVENUECAT_APP_USER_ID_PREFIX|REVENUECAT_WEBHOOK_AUTHORIZATION|REVENUECAT_WEBHOOK_SIGNING_SECRET)=/
' "$env_path" >"$env_candidate"

printf '%s\n' \
  'APPLE_SIGNIN_ENABLED=true' \
  'APPLE_SIGNIN_CLIENT_IDS=com.ironside.challengesphere' \
  'APPLE_TEAM_ID=9CZSSGNN55' \
  'APPLE_KEY_ID=J83R3GYC58' \
  'REVENUECAT_ENABLED=true' \
  'REVENUECAT_ENTITLEMENT_ID=premium' \
  'REVENUECAT_APP_USER_ID_PREFIX=cs_user_' \
  >>"$env_candidate"

append_secure_parameter() {
  local env_name="$1"
  local parameter_name="$2"
  local parameter_value
  parameter_value="$(python3 - "$parameter_name" <<'PY'
import sys

import boto3

value = boto3.client("ssm", region_name="ap-south-1").get_parameter(
    Name=sys.argv[1],
    WithDecryption=True,
)["Parameter"]["Value"]
print(value, end="")
PY
)"
  if [[ -z "$parameter_value" || "$parameter_value" == "None" ]]; then
    echo "Secure parameter is empty: $parameter_name" >&2
    return 1
  fi
  printf '%s=%s\n' "$env_name" "$parameter_value" >>"$env_candidate"
}

append_secure_parameter \
  APPLE_PRIVATE_KEY_BASE64 \
  /challengesphere/prod/apple-private-key-base64
append_secure_parameter \
  APPLE_TOKEN_ENCRYPTION_KEY_BASE64 \
  /challengesphere/prod/apple-token-encryption-key-base64
append_secure_parameter \
  ACCOUNT_DELETION_IDENTITY_HASH_KEY \
  /challengesphere/prod/account-deletion-identity-hash-key
append_secure_parameter \
  REVENUECAT_SECRET_API_KEY \
  /challengesphere/prod/revenuecat-secret-api-key
append_secure_parameter \
  REVENUECAT_WEBHOOK_AUTHORIZATION \
  /challengesphere/prod/revenuecat-webhook-authorization
append_secure_parameter \
  REVENUECAT_WEBHOOK_SIGNING_SECRET \
  /challengesphere/prod/revenuecat-webhook-signing-secret

install -o root -g ubuntu -m 0640 "$env_candidate" "$env_path"
install -o ubuntu -g ubuntu -m 0644 "$candidate_path" "$jar_path"
systemctl restart "$service_name"

healthy=false
for _ in $(seq 1 60); do
  if systemctl is-active --quiet "$service_name" \
    && curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null; then
    healthy=true
    break
  fi
  sleep 2
done

if [[ "$healthy" != true ]]; then
  journalctl -u "$service_name" --since '-5 minutes' --no-pager | tail -n 120 >&2
  false
fi

rollback_required=false
rm -f "$candidate_path" "$env_candidate"

sha256sum "$jar_path"
systemctl is-active "$service_name"
curl -fsS http://127.0.0.1:8080/actuator/health
