# Non-Destructive S3 Runtime Design

## Scope

This pass removes the backend's ability to delete S3 media and closes the cross-user object-mutation path in submission creation and reupload. It intentionally does not address the separate read-only media rebinding, availability, privacy, token, webhook, dependency, or secret-rotation findings from the broader audit.

## Security objective

An authenticated ChallengeSphere user must not be able to cause the backend to delete or mutate another user's S3 object. A compromised or buggy backend runtime must not be able to delete any object or object version in the production media bucket.

The following invariants are mandatory:

1. The moderation failure path never invokes an S3 delete operation.
2. The production backend IAM role cannot call `s3:DeleteObject` or `s3:DeleteObjectVersion`.
3. The production bucket keeps object versions so future overwrites and administrative deletions are recoverable.
4. Submission create and reupload accept only canonical submission keys owned by the authenticated user.
5. Ownership validation happens before cache-control mutation, persistence, or moderation dispatch.
6. A submission contains at most five media keys.

## Root cause

`CreateSubmissionRequest.mediaUrls` accepts arbitrary strings. `SubmissionServiceImpl` currently forwards them to cache-header mutation, stores them, and sends the original list to `SubmissionModerationJob`. The job catches any moderation-processing exception and passes the entire untrusted list to `MediaUrlService.deleteMediaObjects`, which parses bare keys and same-bucket URLs and deletes them with the backend's bucket-wide `s3:DeleteObject` permission.

The authenticated user ID and the existing key convention—`submissions/user_<id>/<media-type>/<timestamp>_<uuid>.<extension>`—are available but are not used as an authorization boundary.

## Application design

### Submission media ownership policy

Add a focused `SubmissionMediaKeyPolicy` component. Its public operation accepts an authenticated user ID and a list of client-supplied media references, then returns immutable normalized keys or throws `ChallengeSphereException` with HTTP 400.

Accepted keys must:

- be nonblank bare S3 object keys, not HTTP(S) or `s3://` URLs;
- contain no query, fragment, control character, comma, backslash, empty segment, `.` segment, or `..` segment;
- start with the exact boundary `submissions/user_<authenticated-id>/`;
- use exactly `SUBMISSION_PHOTO` or `SUBMISSION_VIDEO` as the next path segment;
- end in the server-generated `<13 digit epoch>_<UUID>.<allowed extension>` form;
- use `jpg`, `jpeg`, `png`, or `gif` for photos and `mp4` for videos;
- number no more than five.

Null or empty media lists remain valid so text-only submissions keep working. Duplicate keys are rejected rather than silently normalized.

Submission presign and fallback upload generation derive the object extension from the already validated content type rather than copying the client filename extension. This guarantees that every newly issued key satisfies the same policy: JPEG becomes `.jpg`, PNG becomes `.png`, GIF becomes `.gif`, and MP4 becomes `.mp4`.

### Submission service integration

Both create and reupload normalize and validate media immediately after the authenticated user and request are available. Every later operation uses only the returned canonical list:

1. validate caller-owned keys;
2. apply private cache headers;
3. persist the canonical comma-separated list;
4. dispatch moderation with the canonical immutable list;
5. return the canonical keys in the response.

This prevents cross-user cache-control mutation as well as destructive cleanup abuse. Existing stored submissions are unaffected because read paths do not revalidate historical values.

### Moderation behavior

If strict moderation throws, the job hides the submission and invalidates dependent caches and leaderboard data. It logs that uploaded media was retained for administrative cleanup. It performs no object deletion.

Remove the generic deletion API from `MediaUrlService` and the low-level `deleteObject` operation from `GetSignedUrlForS3`. This makes accidental reintroduction visible as a deliberate new code change.

### Request validation

Add `@Size(max = 5)` to `CreateSubmissionRequest.mediaUrls`. Service-level policy validation remains authoritative so direct service calls and future controller mistakes cannot bypass the limit.

## AWS design

The target bucket is `challengesphere-submissions-prod-ap-south-1` in `ap-south-1`. The intended runtime role is `ChallengeSphereBackendSSMRole` through instance profile `ChallengeSphereBackendSSMProfile`.

After confirming the instance profile is attached to the running backend:

1. Enable bucket versioning.
2. Remove `s3:DeleteObject` from the role's `ChallengeSphereRuntimeAwsAccess` inline policy while preserving its current list, get, put, Rekognition, and RDS permissions.
3. Add a bucket-policy statement that explicitly denies `s3:DeleteObject` and `s3:DeleteObjectVersion` to `ChallengeSphereBackendSSMRole` for every object in the bucket.
4. Re-read the bucket versioning, role policy, and bucket policy to verify the effective configuration.

No object, version, lifecycle rule, or delete marker will be removed in this pass. Historical unversioned deletions cannot be recovered by enabling versioning now.

The currently authenticated CLI principal is the AWS account root. Routine root use is discouraged; mutations will be limited to the reviewed bucket and role policy statements and followed by read-back verification.

## Tests

Test-first coverage will prove:

- canonical caller-owned photo and video keys are accepted;
- presign and fallback uploads generate canonical extensions from content type;
- another user's key is rejected, including `user_1` versus `user_10` boundaries;
- raw arbitrary keys, same-bucket URLs, `s3://` references, traversal-like segments, queries, wrong-purpose keys, media-type/extension mismatches, duplicates, and lists over five are rejected;
- create and reupload reject foreign media before cache mutation or repository save;
- a moderation exception hides the submission but never invokes any media deletion;
- the Maven test suite and package build remain green.

## Rollout and rollback

Application changes should deploy before or alongside the IAM restriction. Removing delete permission is safe for the current product because the audited backend contains only one hard-delete caller: failed-moderation cleanup. Ordinary submission deletion is already a database soft delete.

If an unexpected compatibility problem appears, rollback may restore the previous application release while keeping the IAM delete deny in place. The old moderation cleanup will log an access-denied warning, then continue hiding the submission; cross-user deletion will remain blocked.
