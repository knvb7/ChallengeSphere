#!/bin/bash

set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 <user-id> [bucket] [region]" >&2
  exit 64
fi

starter_user_id="$1"
starter_bucket="${2:-${AWS_S3_BUCKET_NAME:-challengesphere-submissions-prod-ap-south-1}}"
starter_region="${3:-${AWS_REGION:-ap-south-1}}"

if [[ ! "$starter_user_id" =~ ^[1-9][0-9]*$ ]]; then
  echo "The starter user ID must be a positive integer." >&2
  exit 64
fi

for required_command in aws curl file; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Missing required command: $required_command" >&2
    exit 69
  fi
done

starter_tmp_dir=$(mktemp -d)
trap 'rm -rf "$starter_tmp_dir"' EXIT

# destination type | deterministic file name | source Unsplash photo id
starter_manifest=(
  "challenge|1784369000001_00000000-0000-4000-8000-000000000001.jpg|photo-1538805060514-97d9cc17730c"
  "challenge|1784369000002_00000000-0000-4000-8000-000000000002.jpg|photo-1517836357463-d25dfeac3438"
  "challenge|1784369000003_00000000-0000-4000-8000-000000000003.jpg|photo-1571019614242-c5c5dee9f50b"
  "challenge|1784369000004_00000000-0000-4000-8000-000000000004.jpg|photo-1524995997946-a1c2e315a42f"
  "challenge|1784369000005_00000000-0000-4000-8000-000000000005.jpg|photo-1495446815901-a7297e633e8d"
  "challenge|1784369000006_00000000-0000-4000-8000-000000000006.jpg|photo-1544947950-fa07a98d237f"
  "challenge|1784369000007_00000000-0000-4000-8000-000000000007.jpg|photo-1517694712202-14dd9538aa97"
  "challenge|1784369000008_00000000-0000-4000-8000-000000000008.jpg|photo-1461749280684-dccba630e2f6"
  "challenge|1784369000009_00000000-0000-4000-8000-000000000009.jpg|photo-1498050108023-c5249f4df085"
  "challenge|1784369000010_00000000-0000-4000-8000-000000000010.jpg|photo-1506126613408-eca07ce68773"
  "challenge|1784369000011_00000000-0000-4000-8000-000000000011.jpg|photo-1545389336-cf090694435e"
  "challenge|1784369000012_00000000-0000-4000-8000-000000000012.jpg|photo-1499209974431-9dddcece7f88"
  "submission|1784369000101_00000000-0000-4000-8000-000000000101.jpg|photo-1552674605-db6ffd4facb5"
  "submission|1784369000102_00000000-0000-4000-8000-000000000102.jpg|photo-1517836357463-d25dfeac3438"
  "submission|1784369000103_00000000-0000-4000-8000-000000000103.jpg|photo-1538805060514-97d9cc17730c"
  "submission|1784369000104_00000000-0000-4000-8000-000000000104.jpg|photo-1512820790803-83ca734da794"
  "submission|1784369000105_00000000-0000-4000-8000-000000000105.jpg|photo-1495446815901-a7297e633e8d"
  "submission|1784369000106_00000000-0000-4000-8000-000000000106.jpg|photo-1524995997946-a1c2e315a42f"
  "submission|1784369000107_00000000-0000-4000-8000-000000000107.jpg|photo-1461749280684-dccba630e2f6"
  "submission|1784369000108_00000000-0000-4000-8000-000000000108.jpg|photo-1517694712202-14dd9538aa97"
  "submission|1784369000109_00000000-0000-4000-8000-000000000109.jpg|photo-1498050108023-c5249f4df085"
  "submission|1784369000110_00000000-0000-4000-8000-000000000110.jpg|photo-1506126613408-eca07ce68773"
  "submission|1784369000111_00000000-0000-4000-8000-000000000111.jpg|photo-1499209974431-9dddcece7f88"
  "submission|1784369000112_00000000-0000-4000-8000-000000000112.jpg|photo-1545389336-cf090694435e"
)

uploaded_count=0
verified_count=0

for manifest_entry in "${starter_manifest[@]}"; do
  IFS='|' read -r media_type file_name source_photo_id <<< "$manifest_entry"
  if [[ "$media_type" == "challenge" ]]; then
    object_key="challenges/user_${starter_user_id}/covers/${file_name}"
  else
    object_key="submissions/user_${starter_user_id}/SUBMISSION_PHOTO/${file_name}"
  fi

  existing_size=$(aws s3api head-object \
    --region "$starter_region" \
    --bucket "$starter_bucket" \
    --key "$object_key" \
    --query ContentLength \
    --output text 2>/dev/null || true)
  if [[ "$existing_size" =~ ^[1-9][0-9]*$ ]]; then
    verified_count=$((verified_count + 1))
    continue
  fi

  local_image="$starter_tmp_dir/$file_name"
  source_url="https://images.unsplash.com/${source_photo_id}?auto=format&fit=crop&w=1200&q=85&fm=jpg"
  curl --fail --location --silent --show-error --retry 3 \
    --header 'Accept: image/jpeg' \
    --output "$local_image" \
    "$source_url"

  mime_type=$(file --brief --mime-type "$local_image")
  if [[ "$mime_type" != "image/jpeg" || ! -s "$local_image" ]]; then
    echo "Downloaded media is not a non-empty JPEG for $object_key" >&2
    exit 65
  fi

  aws s3api put-object \
    --region "$starter_region" \
    --bucket "$starter_bucket" \
    --key "$object_key" \
    --body "$local_image" \
    --content-type image/jpeg \
    --cache-control 'private, max-age=3600' \
    --if-none-match '*' \
    >/dev/null

  uploaded_count=$((uploaded_count + 1))
  verified_count=$((verified_count + 1))
done

printf '{"bucket":"%s","userId":%s,"uploaded":%s,"verified":%s}\n' \
  "$starter_bucket" \
  "$starter_user_id" \
  "$uploaded_count" \
  "$verified_count"
