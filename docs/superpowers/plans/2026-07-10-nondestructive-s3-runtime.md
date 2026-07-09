# Non-Destructive S3 Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent authenticated users or the backend runtime from deleting S3 media and reject cross-user submission keys before any S3 mutation.

**Architecture:** A pure `SubmissionMediaKeyPolicy` establishes the authenticated user and submission-purpose boundary. Submission create/reupload use only policy-returned canonical keys, moderation retains failed media, and low-level delete APIs are removed. AWS versioning plus both identity-policy removal and a bucket-policy deny make object deletion impossible for the EC2 runtime even if application code regresses.

**Tech Stack:** Java 17, Spring Boot, Jakarta Validation, JUnit 5, Mockito, AWS CLI v2, S3, IAM.

## Global Constraints

- Preserve every unrelated tracked and untracked user change in the dirty `aws-migration` worktree.
- Do not delete, list, download, overwrite, copy, or tag any production S3 object.
- Do not add a lifecycle expiration rule in this pass.
- Submission media must remain optional and limited to five keys.
- Only canonical bare keys owned by the authenticated user are accepted; external and `s3://` URLs are rejected.
- Runtime `s3:GetObject` and `s3:PutObject` behavior must remain available.
- Use test-first red/green cycles for every production-code behavior change.

---

## File structure

- Create `src/main/java/com/ironside/challengesphere/service/impl/SubmissionMediaKeyPolicy.java`: pure owner/purpose/key-shape validation and normalization.
- Create `src/test/java/com/ironside/challengesphere/service/impl/SubmissionMediaKeyPolicyTest.java`: canonical and hostile-key policy cases.
- Create `src/test/java/com/ironside/challengesphere/service/impl/SubmissionMediaSecurityIntegrationTest.java`: service-boundary rejection before repositories or S3 mutation.
- Create `src/test/java/com/ironside/challengesphere/service/impl/SubmissionModerationJobTest.java`: moderation failures hide without storage cleanup.
- Create `src/test/java/com/ironside/challengesphere/service/impl/S3DeletionCapabilityTest.java`: architectural guard that delete APIs remain absent.
- Modify `src/main/java/com/ironside/challengesphere/api/request/CreateSubmissionRequest.java`: controller-level five-key cap.
- Modify `src/main/java/com/ironside/challengesphere/service/impl/SubmissionServiceImpl.java`: policy integration and canonical extension generation.
- Modify `src/main/java/com/ironside/challengesphere/service/impl/SubmissionModerationJob.java`: retain media on moderation infrastructure failures.
- Modify `src/main/java/com/ironside/challengesphere/service/impl/MediaUrlService.java`: remove generic deletion surface.
- Modify `src/main/java/com/ironside/challengesphere/util/GetSignedUrlForS3.java`: remove low-level delete operation/import.
- Mutate AWS configuration only after application tests pass: bucket versioning, runtime inline policy, and bucket delete deny.

---

### Task 1: Canonical submission media policy

**Files:**
- Create: `src/test/java/com/ironside/challengesphere/service/impl/SubmissionMediaKeyPolicyTest.java`
- Create: `src/main/java/com/ironside/challengesphere/service/impl/SubmissionMediaKeyPolicy.java`

**Interfaces:**
- Consumes: authenticated `Long userId`, nullable `List<String> mediaKeys`, media purpose, content type, epoch milliseconds, and UUID.
- Produces: `List<String> validateAndNormalize(Long userId, List<String> mediaKeys) throws ChallengeSphereException` and `String createKey(Long userId, MediaTypes mediaType, ContentType contentType, long epochMillis, UUID uniqueId) throws ChallengeSphereException`.

- [ ] **Step 1: Write the failing policy tests**

Create tests covering this table with user `7`:

```java
private static final String PHOTO =
        "submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";
private static final String VIDEO =
        "submissions/user_7/SUBMISSION_VIDEO/1752148800000_123e4567-e89b-12d3-a456-426614174001.mp4";

@Test
void acceptsCanonicalOwnedPhotoAndVideo() throws Exception {
    assertEquals(List.of(PHOTO, VIDEO), policy.validateAndNormalize(7L, List.of(PHOTO, VIDEO)));
}

@Test
void allowsTextOnlySubmission() throws Exception {
    assertEquals(List.of(), policy.validateAndNormalize(7L, null));
    assertEquals(List.of(), policy.validateAndNormalize(7L, List.of()));
}

@ParameterizedTest
@ValueSource(strings = {
        "submissions/user_8/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg",
        "submissions/user_70/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg",
        "profiles/user_7/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg",
        "victim.jpg",
        "https://bucket.s3.amazonaws.com/submissions/user_7/SUBMISSION_PHOTO/victim.jpg",
        "s3://bucket/submissions/user_7/SUBMISSION_PHOTO/victim.jpg",
        "submissions/user_7/../user_8/SUBMISSION_PHOTO/victim.jpg",
        "submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg?x=1",
        "submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.mp4",
        "submissions/user_7/SUBMISSION_VIDEO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg"
})
void rejectsNonCanonicalOrForeignKeys(String key) {
    ChallengeSphereException error = assertThrows(
            ChallengeSphereException.class,
            () -> policy.validateAndNormalize(7L, List.of(key)));
    assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
}

@Test
void rejectsDuplicateAndOversizedLists() {
    assertThrows(ChallengeSphereException.class,
            () -> policy.validateAndNormalize(7L, List.of(PHOTO, PHOTO)));
    assertThrows(ChallengeSphereException.class,
            () -> policy.validateAndNormalize(7L, List.of(PHOTO, VIDEO, PHOTO, VIDEO, PHOTO, VIDEO)));
}

@ParameterizedTest
@CsvSource({
        "SUBMISSION_PHOTO,IMAGE_JPEG,.jpg",
        "SUBMISSION_PHOTO,IMAGE_PNG,.png",
        "SUBMISSION_PHOTO,IMAGE_GIF,.gif",
        "SUBMISSION_VIDEO,VIDEO_MP4,.mp4"
})
void createsCanonicalKeyFromValidatedMediaTypeAndContentType(
        MediaTypes mediaType,
        GeneratePresignedUrlsRequest.ContentType contentType,
        String expectedSuffix) throws Exception {
    String key = policy.createKey(
            7L,
            mediaType,
            contentType,
            1752148800000L,
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    assertEquals(
            "submissions/user_7/" + mediaType + "/1752148800000_"
                    + "123e4567-e89b-12d3-a456-426614174000" + expectedSuffix,
            key);
}

@Test
void keyCreationRejectsMediaAndContentTypeMismatch() {
    assertThrows(ChallengeSphereException.class, () -> policy.createKey(
            7L,
            MediaTypes.SUBMISSION_PHOTO,
            GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4,
            1752148800000L,
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000")));
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./mvnw -Dtest=SubmissionMediaKeyPolicyTest test
```

Expected: test compilation fails because `SubmissionMediaKeyPolicy` does not exist.

- [ ] **Step 3: Implement the minimal policy**

Use a single anchored regex with captured owner, media type, and lowercase extension:

```java
@Component
public class SubmissionMediaKeyPolicy {
    private static final Logger logger = LogManager.getLogger(SubmissionMediaKeyPolicy.class);
    private static final int MAX_MEDIA_KEYS = 5;
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^submissions/user_(\\d+)/(SUBMISSION_PHOTO|SUBMISSION_VIDEO)/"
                    + "\\d{13}_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|jpeg|png|gif|mp4)$");

    public List<String> validateAndNormalize(Long userId, List<String> mediaKeys)
            throws ChallengeSphereException {
        if (mediaKeys == null || mediaKeys.isEmpty()) return List.of();
        if (userId == null || mediaKeys.size() > MAX_MEDIA_KEYS) throw invalid();

        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>(mediaKeys.size());
        for (String raw : mediaKeys) {
            String key = raw == null ? "" : raw.trim();
            Matcher matcher = KEY_PATTERN.matcher(key);
            if (!matcher.matches()
                    || !Long.toString(userId).equals(matcher.group(1))
                    || !extensionMatchesType(matcher.group(2), matcher.group(3))
                    || !seen.add(key)) {
                throw invalid();
            }
            normalized.add(key);
        }
        return List.copyOf(normalized);
    }

    private boolean extensionMatchesType(String type, String extension) {
        return "SUBMISSION_VIDEO".equals(type)
                ? "mp4".equals(extension)
                : !"mp4".equals(extension);
    }

    public String createKey(
            Long userId,
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType,
            long epochMillis,
            UUID uniqueId) throws ChallengeSphereException {
        if (userId == null || mediaType == null || contentType == null || uniqueId == null
                || epochMillis < 1_000_000_000_000L
                || (mediaType == MediaTypes.SUBMISSION_PHOTO
                    && contentType == GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4)
                || (mediaType == MediaTypes.SUBMISSION_VIDEO
                    && contentType != GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4)
                || (mediaType != MediaTypes.SUBMISSION_PHOTO
                    && mediaType != MediaTypes.SUBMISSION_VIDEO)) {
            throw invalid();
        }
        String extension = switch (contentType) {
            case IMAGE_JPEG -> ".jpg";
            case IMAGE_PNG -> ".png";
            case IMAGE_GIF -> ".gif";
            case VIDEO_MP4 -> ".mp4";
        };
        return "submissions/user_%d/%s/%d_%s%s".formatted(
                userId, mediaType.name(), epochMillis, uniqueId, extension);
    }

    private ChallengeSphereException invalid() {
        return new ChallengeSphereException(
                "Invalid submission media reference", HttpStatus.BAD_REQUEST, logger);
    }
}
```

- [ ] **Step 4: Run the policy tests and verify GREEN**

Run `./mvnw -Dtest=SubmissionMediaKeyPolicyTest test`.

Expected: all policy tests pass with zero failures and errors.

---

### Task 2: Enforce ownership before submission-side effects

**Files:**
- Create: `src/test/java/com/ironside/challengesphere/service/impl/SubmissionMediaSecurityIntegrationTest.java`
- Modify: `src/main/java/com/ironside/challengesphere/api/request/CreateSubmissionRequest.java:27`
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/SubmissionServiceImpl.java:95-99,109-293,412-473,677-846`

**Interfaces:**
- Consumes: `SubmissionMediaKeyPolicy.validateAndNormalize` from Task 1.
- Produces: no API response-shape change; create/reupload use canonical owned keys only.

- [ ] **Step 1: Write failing service-boundary tests**

Instantiate `SubmissionServiceImpl`, inject a real `SubmissionMediaKeyPolicy`, mocked `SubmissionRepository`, and mocked `MediaUrlService` with `ReflectionTestUtils`. Submit a `user_8` key while `request.userId` is `7`:

```java
private static final String FOREIGN_KEY =
        "submissions/user_8/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";

@BeforeEach
void setUp() {
    service = new SubmissionServiceImpl();
    submissionRepository = mock(SubmissionRepository.class);
    mediaUrlService = mock(MediaUrlService.class);
    ReflectionTestUtils.setField(service, "submissionMediaKeyPolicy", new SubmissionMediaKeyPolicy());
    ReflectionTestUtils.setField(service, "submissionRepository", submissionRepository);
    ReflectionTestUtils.setField(service, "mediaUrlService", mediaUrlService);
}

@Test
void createRejectsForeignMediaBeforeAnySideEffect() {
    CreateSubmissionRequest request = new CreateSubmissionRequest();
    request.setUserId(7L);
    request.setChallengeId(3L);
    request.setDayNumber(1);
    request.setDescription("proof");
    request.setMediaUrls(List.of(FOREIGN_KEY));

ChallengeSphereException error = assertThrows(
        ChallengeSphereException.class,
        () -> service.createSubmission(request));
assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
verifyNoInteractions(submissionRepository, mediaUrlService);
}

@Test
void reuploadRejectsForeignMediaBeforeAnySideEffect() {
    CreateSubmissionRequest request = new CreateSubmissionRequest();
    request.setUserId(7L);
    request.setDescription("replacement");
    request.setMediaUrls(List.of(FOREIGN_KEY));

    ChallengeSphereException error = assertThrows(
            ChallengeSphereException.class,
            () -> service.reuploadSubmission(99L, request));
    assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    verifyNoInteractions(submissionRepository, mediaUrlService);
}
```

- [ ] **Step 2: Run and verify RED**

Run `./mvnw -Dtest=SubmissionMediaSecurityIntegrationTest test`.

Expected: failure because `SubmissionServiceImpl` neither declares nor invokes `SubmissionMediaKeyPolicy`.

- [ ] **Step 3: Apply policy results everywhere**

Add an autowired field:

```java
@Autowired
private SubmissionMediaKeyPolicy submissionMediaKeyPolicy;
```

At the start of create and reupload, derive:

```java
List<String> mediaKeys = submissionMediaKeyPolicy.validateAndNormalize(
        userId, request.getMediaUrls());
```

Replace every create/reupload use of `request.getMediaUrls()` in cache mutation, moderation, persistence, async dispatch, and success responses with `mediaKeys`.

Add controller-level validation:

```java
@Size(max = 5, message = "A submission can include at most 5 media files")
private List<String> mediaUrls;
```

- [ ] **Step 4: Run and verify GREEN**

Run:

```bash
./mvnw -Dtest=SubmissionMediaKeyPolicyTest,SubmissionMediaSecurityIntegrationTest test
```

Expected: both test classes pass.

- [ ] **Step 5: Write failing factory-delegation tests and verify RED**

In `SubmissionMediaSecurityIntegrationTest`, inject a mocked `SubmissionMediaKeyPolicy` plus a mocked `UserRepository`. Build one JPEG `MediaFileInfo`, make `userRepository.existsById(7L)` return true, invoke `generatePresignedUrls`, and verify:

```java
verify(keyPolicy).createKey(
        eq(7L),
        eq(MediaTypes.SUBMISSION_PHOTO),
        eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
        anyLong(),
        any(UUID.class));
```

Use the same assertion for `uploadSubmissionMedia` with a nonempty mocked `MultipartFile`. Configure `keyPolicy.createKey` to throw a `ChallengeSphereException` so each flow stops before static S3 signing/upload and cannot contact AWS.

Run `./mvnw -Dtest=SubmissionMediaSecurityIntegrationTest test`.

Expected: both verifications fail because the service currently constructs keys directly from client filenames.

- [ ] **Step 6: Make key generation use the tested policy factory**

Replace submission key construction in both the presign and fallback flows with:

```java
String fileKey = submissionMediaKeyPolicy.createKey(
        userId,
        mediaFile.getMediaType(),
        mediaFile.getContentType(),
        System.currentTimeMillis(),
        UUID.randomUUID());
```

For fallback, pass the method's `mediaType` and `contentType`. Remove the now-unused submission filename extension helper. Task 1's exact factory tests are the behavior-level proof that generated keys use canonical content-type-derived extensions.

- [ ] **Step 7: Run and verify GREEN**

Run `./mvnw -Dtest=SubmissionMediaKeyPolicyTest,SubmissionMediaSecurityIntegrationTest test`.

Expected: all tests pass.

---

### Task 3: Eliminate application deletion capability

**Files:**
- Create: `src/test/java/com/ironside/challengesphere/service/impl/SubmissionModerationJobTest.java`
- Create: `src/test/java/com/ironside/challengesphere/service/impl/S3DeletionCapabilityTest.java`
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/SubmissionModerationJob.java:39-43,89-94`
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/MediaUrlService.java:152-179`
- Modify: `src/main/java/com/ironside/challengesphere/util/GetSignedUrlForS3.java:16,151-165`

**Interfaces:**
- Consumes: existing moderation result and authoritative submission row.
- Produces: moderation infrastructure failure hides the row and retains all media; no S3 delete API remains.

- [ ] **Step 1: Write the failing moderation test**

Create an active submission, make `contentModerationService.scanStrict` throw, inject mocked repositories/cache/leaderboard, and do not inject `MediaUrlService`:

```java
Submission submission = new Submission();
submission.setId(99L);
submission.setUserId(7L);
submission.setChallengeId(3L);
submission.setActive(true);
when(submissionRepository.findById(99L)).thenReturn(Optional.of(submission));
when(contentModerationService.scanStrict(anyList()))
        .thenThrow(new IllegalStateException("moderation unavailable"));

assertDoesNotThrow(() -> job.moderateSubmissionAsync(
        99L,
        7L,
        3L,
        List.of("submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg")));
assertFalse(submission.getActive());
verify(submissionRepository).save(submission);
```

Expected current behavior: the test fails because the catch block dereferences `mediaUrlService` before hiding the submission.

- [ ] **Step 2: Write the failing architectural test**

```java
@Test
void runtimeExposesNoS3DeletionApi() {
    assertFalse(hasMethod(MediaUrlService.class, "deleteMediaObjects"));
    assertFalse(hasMethod(GetSignedUrlForS3.class, "deleteObject"));
}
```

Expected current behavior: both assertions fail.

- [ ] **Step 3: Run both tests and verify RED**

Run:

```bash
./mvnw -Dtest=SubmissionModerationJobTest,S3DeletionCapabilityTest test
```

- [ ] **Step 4: Remove deletion behavior and APIs**

In the moderation catch block, replace deletion with a non-sensitive log and retain hide/invalidation:

```java
logger.error(
        "Async moderation failed for submission {}; uploaded media retained for administrative cleanup: {}",
        submissionId,
        e.getClass().getSimpleName());
hideSubmission(submission);
invalidateAfterRemoval(submission);
```

Remove the unused `MediaUrlService` field from the job, `deleteMediaObjects` and its deletion-only key collection helper from `MediaUrlService`, and `deleteObject` plus `DeleteObjectRequest` from `GetSignedUrlForS3`.

- [ ] **Step 5: Run and verify GREEN**

Run:

```bash
./mvnw -Dtest=SubmissionModerationJobTest,S3DeletionCapabilityTest test
```

Expected: both test classes pass.

---

### Task 4: Verify application behavior before cloud mutation

**Files:**
- Verify all files from Tasks 1–3.

**Interfaces:**
- Produces: evidence that the application can safely run without S3 delete permission.

- [ ] **Step 1: Run focused security tests**

```bash
./mvnw -Dtest=SubmissionMediaKeyPolicyTest,SubmissionMediaSecurityIntegrationTest,SubmissionModerationJobTest,S3DeletionCapabilityTest test
```

Expected: zero failures and errors.

- [ ] **Step 2: Run the complete backend suite**

```bash
./mvnw test
```

Expected: `BUILD SUCCESS`, zero failures and errors.

- [ ] **Step 3: Build the package**

```bash
./mvnw -DskipTests package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Inspect only scoped diffs**

Run `git diff --check` and inspect diffs for the files listed in this plan. Confirm unrelated dirty changes remain untouched.

---

### Task 5: Apply production AWS deletion guardrails

**Files:**
- No repository file changes.
- AWS resources: S3 bucket `challengesphere-submissions-prod-ap-south-1`; IAM role `ChallengeSphereBackendSSMRole`; inline policy `ChallengeSphereRuntimeAwsAccess`.

**Interfaces:**
- Preserves: bucket list/get/put, Rekognition moderation, and RDS connect permissions.
- Removes: runtime object and object-version deletion.
- Adds: recoverable object versions for future writes.

- [ ] **Step 1: Reconfirm identity and runtime instance-profile attachment**

Run:

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --profile default --query Account --output text)
aws ec2 describe-instances \
  --profile default \
  --region ap-south-1 \
  --filters \
    "Name=iam-instance-profile.arn,Values=arn:aws:iam::$ACCOUNT_ID:instance-profile/ChallengeSphereBackendSSMProfile" \
    "Name=instance-state-name,Values=pending,running" \
  --query 'Reservations[].Instances[].{InstanceId:InstanceId,State:State.Name,Profile:IamInstanceProfile.Arn}' \
  --output table
```

Require at least one result, or stop and identify the actual runtime role before changing IAM.

- [ ] **Step 2: Re-read and validate current policies**

Confirm bucket policy remains absent or merge the deny into the current policy without replacing unrelated statements. Confirm `ChallengeSphereRuntimeAwsAccess` still contains only the audited statements before editing it.

- [ ] **Step 3: Enable bucket versioning**

```bash
aws s3api put-bucket-versioning \
  --profile default \
  --region ap-south-1 \
  --bucket challengesphere-submissions-prod-ap-south-1 \
  --versioning-configuration Status=Enabled
```

- [ ] **Step 4: Remove runtime `s3:DeleteObject`**

Read the live inline policy, remove only the exact `s3:DeleteObject` action with `jq`, and pass the resulting JSON to `aws iam put-role-policy`. Assert the transformed policy still includes `s3:GetObject` and `s3:PutObject` and no delete action before sending it.

```bash
CURRENT_ROLE_POLICY=$(aws iam get-role-policy \
  --profile default \
  --role-name ChallengeSphereBackendSSMRole \
  --policy-name ChallengeSphereRuntimeAwsAccess \
  --query PolicyDocument \
  --output json)
UPDATED_ROLE_POLICY=$(jq -c \
  '(.Statement[] | select((.Action | arrays) and (.Action | index("s3:DeleteObject"))) | .Action)
   |= map(select(. != "s3:DeleteObject"))' \
  <<<"$CURRENT_ROLE_POLICY")
jq -e '[.Statement[].Action] | flatten
  | index("s3:DeleteObject") == null' <<<"$UPDATED_ROLE_POLICY"
jq -e '[.Statement[].Action] | flatten
  | (index("s3:GetObject") != null and index("s3:PutObject") != null)' <<<"$UPDATED_ROLE_POLICY"
aws iam put-role-policy \
  --profile default \
  --role-name ChallengeSphereBackendSSMRole \
  --policy-name ChallengeSphereRuntimeAwsAccess \
  --policy-document "$UPDATED_ROLE_POLICY"
```

- [ ] **Step 5: Add the explicit bucket-policy deny**

Apply or merge this statement using the current account ID without printing it:

```json
{
  "Sid": "DenyChallengeSphereBackendRuntimeDeletion",
  "Effect": "Deny",
  "Principal": "*",
  "Action": ["s3:DeleteObject", "s3:DeleteObjectVersion"],
  "Resource": "arn:aws:s3:::challengesphere-submissions-prod-ap-south-1/*",
  "Condition": {
    "ArnEquals": {
      "aws:PrincipalArn": "arn:aws:iam::${ACCOUNT_ID}:role/ChallengeSphereBackendSSMRole"
    }
  }
}
```

Because the audited bucket has no policy, re-read it immediately before this command and stop if one has appeared. Then construct and apply the exact deny without writing the account ID to disk:

```bash
ROLE_ARN="arn:aws:iam::$ACCOUNT_ID:role/ChallengeSphereBackendSSMRole"
BUCKET_POLICY=$(jq -cn --arg roleArn "$ROLE_ARN" '{
  Version: "2012-10-17",
  Statement: [{
    Sid: "DenyChallengeSphereBackendRuntimeDeletion",
    Effect: "Deny",
    Principal: "*",
    Action: ["s3:DeleteObject", "s3:DeleteObjectVersion"],
    Resource: "arn:aws:s3:::challengesphere-submissions-prod-ap-south-1/*",
    Condition: {ArnEquals: {"aws:PrincipalArn": $roleArn}}
  }]
}')
aws s3api put-bucket-policy \
  --profile default \
  --region ap-south-1 \
  --bucket challengesphere-submissions-prod-ap-south-1 \
  --policy "$BUCKET_POLICY"
```

- [ ] **Step 6: Read back and verify AWS state**

Verify:

- `get-bucket-versioning` returns `Enabled`;
- the inline policy contains get/put but neither delete action;
- the bucket policy contains the exact explicit deny for the backend role;
- `simulate-principal-policy` returns `implicitDeny` or `explicitDeny` for `s3:DeleteObject` and `allowed` for `s3:PutObject` on a synthetic object ARN.

Do not issue an actual S3 PUT or DELETE as a test.

---

### Task 6: Final verification and handoff

**Files:**
- All scoped code, tests, design, and plan files.

**Interfaces:**
- Produces: reproducible evidence and rollback notes.

- [ ] **Step 1: Re-run application verification after AWS changes**

Run `./mvnw test` and `./mvnw -DskipTests package` fresh.

- [ ] **Step 2: Re-run AWS read-back verification**

Run the versioning, role-policy, bucket-policy, and IAM simulation queries fresh.

- [ ] **Step 3: Review the final diff and requirements**

Check every invariant in the design document against code, tests, and AWS output. Use `git diff --check` and ensure no secret values or full account identifiers were added to repository files.

- [ ] **Step 4: Report exact results and remaining scope**

Report test counts, build exit status, versioning status, IAM simulation decisions, changed files, and the fact that historical unversioned deletions remain unrecoverable. List broader audit findings as intentionally deferred.
