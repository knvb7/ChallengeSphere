# Active Challenge Participation Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce a configurable maximum of 10 active, incomplete challenge participations per user and make every frontend join flow use and display the live backend policy.

**Architecture:** The backend owns the policy through a validated configuration property, serializes joins for each user with a pessimistic user-row lock, and counts only active, incomplete memberships in active challenges. An authenticated policy endpoint lets the Expo client preflight joins without duplicating production configuration, while HTTP 409 remains authoritative when client state is stale.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL, JUnit 5, Mockito, TypeScript, Expo Router, React Query, Axios, Node regression scripts.

---

## File Structure

Backend:

- Create `src/main/java/com/ironside/challengesphere/config/ChallengeParticipationProperties.java` for validated policy configuration.
- Create `src/main/java/com/ironside/challengesphere/api/response/ChallengeParticipationPolicyResponse.java` for the policy API contract.
- Modify `src/main/java/com/ironside/challengesphere/repository/UserRepository.java` to lock a joining user.
- Modify `src/main/java/com/ironside/challengesphere/repository/UserChallengeRepository.java` to count slot-consuming participations.
- Modify `src/main/java/com/ironside/challengesphere/service/impl/ChallengeServiceImpl.java` to enforce the limit before save.
- Modify `src/main/java/com/ironside/challengesphere/controller/ChallengeController.java` to expose the policy.
- Modify `src/main/resources/application.properties` and production/local profiles to bind the environment variable.
- Create `src/test/java/com/ironside/challengesphere/service/impl/ChallengeParticipationLimitTest.java`.
- Create `src/test/java/com/ironside/challengesphere/controller/ChallengeParticipationPolicyControllerTest.java`.

Frontend:

- Modify `../ChallengeSphere-Frontend/types/api.ts` with the policy response type.
- Modify `../ChallengeSphere-Frontend/services/challengeService.ts` with the policy request.
- Modify `../ChallengeSphere-Frontend/hooks/useChallenges.ts` to fetch policy before joining and normalize backend errors.
- Modify `../ChallengeSphere-Frontend/app/(tabs)/global.tsx` to display the live limit.
- Modify `../ChallengeSphere-Frontend/app/challenge-details.tsx` and `app/global-challenge-details.tsx` to display authoritative join errors.
- Create `../ChallengeSphere-Frontend/scripts/test-active-challenge-limit.mjs` as a focused frontend contract regression.

### Task 1: Backend Policy Configuration and API Contract

**Files:**
- Create: `src/main/java/com/ironside/challengesphere/config/ChallengeParticipationProperties.java`
- Create: `src/main/java/com/ironside/challengesphere/api/response/ChallengeParticipationPolicyResponse.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application-prod.properties`
- Modify: `src/main/resources/application-local.properties`
- Create: `src/test/java/com/ironside/challengesphere/controller/ChallengeParticipationPolicyControllerTest.java`
- Modify: `src/main/java/com/ironside/challengesphere/controller/ChallengeController.java`

- [ ] **Step 1: Write the failing policy endpoint test**

Create a direct controller test that sets mocked dependencies with `ReflectionTestUtils`, configures a limit of 7, calls `getParticipationPolicy()`, and asserts:

```java
assertEquals(HttpStatus.OK, response.getStatusCode());
assertNotNull(response.getBody());
assertEquals(7, response.getBody().getMaxActiveChallenges());
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./mvnw -Dtest=ChallengeParticipationPolicyControllerTest test
```

Expected: compilation fails because the properties class, response DTO, and controller method do not exist.

- [ ] **Step 3: Add the validated configuration property and response**

Implement:

```java
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.challenge")
public class ChallengeParticipationProperties {
    @Min(1)
    private int maxActiveParticipations = 10;
}
```

Implement:

```java
@Data
@AllArgsConstructor
public class ChallengeParticipationPolicyResponse {
    private int maxActiveChallenges;
}
```

Add to each application properties file:

```properties
app.challenge.max-active-participations=${MAX_ACTIVE_CHALLENGE_PARTICIPATIONS:10}
```

- [ ] **Step 4: Add the authenticated policy endpoint**

Inject `ChallengeParticipationProperties` into `ChallengeController` and add:

```java
@GetMapping("/participation-policy")
public ResponseEntity<ChallengeParticipationPolicyResponse> getParticipationPolicy() {
    return ResponseEntity.ok(new ChallengeParticipationPolicyResponse(
            challengeParticipationProperties.getMaxActiveParticipations()
    ));
}
```

- [ ] **Step 5: Run the focused test and verify GREEN**

Run:

```bash
./mvnw -Dtest=ChallengeParticipationPolicyControllerTest test
```

Expected: PASS.

### Task 2: Authoritative Backend Limit and Concurrency Protection

**Files:**
- Create: `src/test/java/com/ironside/challengesphere/service/impl/ChallengeParticipationLimitTest.java`
- Modify: `src/main/java/com/ironside/challengesphere/repository/UserRepository.java`
- Modify: `src/main/java/com/ironside/challengesphere/repository/UserChallengeRepository.java`
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/ChallengeServiceImpl.java`

- [ ] **Step 1: Write failing service tests**

Create mocked service tests for:

```java
@Test
void tenthActiveParticipationIsAllowed() throws Exception

@Test
void eleventhActiveParticipationIsRejected() throws Exception

@Test
void locksUserBeforeCountingActiveParticipations() throws Exception
```

Use an active public challenge, an existing user, no active association for the target challenge, and a configured maximum of 10. Stub the count as 9 for the allowed case and 10 for the rejected case. Assert the rejected exception has `HttpStatus.CONFLICT`, contains `10`, and never calls `saveAll`.

Use Mockito `InOrder` to verify:

```java
inOrder.verify(userRepository).findByIdForUpdate(userId);
inOrder.verify(userChallengeRepository).countSlotConsumingParticipations(userId);
```

- [ ] **Step 2: Run the service tests and verify RED**

Run:

```bash
./mvnw -Dtest=ChallengeParticipationLimitTest test
```

Expected: compilation fails because the lock and count repository methods do not exist; after adding signatures only, behavior still fails because joins are not capped.

- [ ] **Step 3: Add the lock and count queries**

Add to `UserRepository`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT u FROM User u WHERE u.id = :userId")
Optional<User> findByIdForUpdate(@Param("userId") Long userId);
```

Add to `UserChallengeRepository`:

```java
@Query(value = """
        SELECT COUNT(*)
        FROM challengesphere.user_challenges uc
        JOIN challengesphere.challenges c ON c.id = uc.challenge_id
        WHERE uc.user_id = :userId
          AND uc.active = true
          AND COALESCE(uc.completed, false) = false
          AND c.active = true
        """, nativeQuery = true)
long countSlotConsumingParticipations(@Param("userId") Long userId);
```

- [ ] **Step 4: Enforce the policy immediately before creating or reactivating membership**

Inject `ChallengeParticipationProperties` into `ChallengeServiceImpl`. After existing and already-active associations are removed from `successfulUserIds`, lock and validate each remaining user in stable ID order:

```java
for (Long userId : successfulUserIds.stream().sorted().toList()) {
    userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new ChallengeSphereException(
                    "User not found with id: " + userId,
                    HttpStatus.NOT_FOUND,
                    logger
            ));
    long activeParticipations =
            userChallengeRepository.countSlotConsumingParticipations(userId);
    int limit = challengeParticipationProperties.getMaxActiveParticipations();
    if (activeParticipations >= limit) {
        throw new ChallengeSphereException(
                "You can participate in at most " + limit
                        + " active challenges at a time. Complete or leave a challenge before joining another.",
                HttpStatus.CONFLICT,
                logger
        );
    }
}
```

This runs inside the existing transaction and before any membership, counter, notification, or cache mutation.

- [ ] **Step 5: Run the service tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=ChallengeParticipationLimitTest test
```

Expected: all three tests PASS.

- [ ] **Step 6: Run the backend focused test group**

Run:

```bash
./mvnw -Dtest=ChallengeParticipationLimitTest,ChallengeParticipationPolicyControllerTest,InviteSecurityRegressionTest test
```

Expected: PASS, confirming direct, invite, and shared join behavior remains compatible.

### Task 3: Frontend Policy Client and Join Guard

**Files:**
- Create: `../ChallengeSphere-Frontend/scripts/test-active-challenge-limit.mjs`
- Modify: `../ChallengeSphere-Frontend/types/api.ts`
- Modify: `../ChallengeSphere-Frontend/services/challengeService.ts`
- Modify: `../ChallengeSphere-Frontend/hooks/useChallenges.ts`

- [ ] **Step 1: Write the failing frontend contract regression**

The Node script reads the three source files and asserts:

```js
assert.match(types, /maxActiveChallenges:\s*number/);
assert.match(service, /participation-policy/);
assert.match(hooks, /DEFAULT_MAX_ACTIVE_CHALLENGES\s*=\s*10/);
assert.doesNotMatch(hooks, /MAX_ACTIVE_CHALLENGES\s*=\s*15/);
assert.match(hooks, /fetchQuery/);
assert.match(hooks, /getApiErrorMessage/);
```

- [ ] **Step 2: Run the frontend regression and verify RED**

Run:

```bash
node scripts/test-active-challenge-limit.mjs
```

Expected: FAIL because the frontend still hardcodes 15 and has no policy API.

- [ ] **Step 3: Add the policy API type and service method**

Add:

```ts
export interface ChallengeParticipationPolicyResponse {
  maxActiveChallenges: number;
}
```

Add:

```ts
async getParticipationPolicy(): Promise<ChallengeParticipationPolicyResponse> {
  const response = await apiClient.get<ChallengeParticipationPolicyResponse>(
    '/challenges/participation-policy'
  );
  return response.data;
}
```

- [ ] **Step 4: Add the cached policy query and authoritative mutation behavior**

In `useChallenges.ts`, define:

```ts
export const DEFAULT_MAX_ACTIVE_CHALLENGES = 10;

export const useChallengeParticipationPolicy = () =>
  useQuery({
    queryKey: [...challengeKeys.all, 'participation-policy'],
    queryFn: () => challengeService.getParticipationPolicy(),
    staleTime: 5 * 60 * 1000,
  });
```

In `useJoinChallenge`, call `queryClient.fetchQuery` for the policy, falling back to 10 only if policy retrieval fails. Compare cached `totalActive` against the resolved limit. Wrap API failures as:

```ts
throw new Error(getApiErrorMessage(error, 'Failed to join challenge.'));
```

Do not invalidate successful-query caches on rejection.

- [ ] **Step 5: Run the frontend regression and verify GREEN**

Run:

```bash
node scripts/test-active-challenge-limit.mjs
```

Expected: PASS.

### Task 4: Frontend Join Presentation

**Files:**
- Modify: `../ChallengeSphere-Frontend/app/(tabs)/global.tsx`
- Modify: `../ChallengeSphere-Frontend/app/challenge-details.tsx`
- Modify: `../ChallengeSphere-Frontend/app/global-challenge-details.tsx`
- Modify: `../ChallengeSphere-Frontend/scripts/test-active-challenge-limit.mjs`

- [ ] **Step 1: Extend the frontend regression to require live UI policy and error messages**

Assert that the global screen imports and calls `useChallengeParticipationPolicy`, uses `maxActiveChallenges`, and no longer imports `MAX_ACTIVE_CHALLENGES`. Assert both detail screens use their `onError` argument's message.

- [ ] **Step 2: Run the regression and verify RED**

Run:

```bash
node scripts/test-active-challenge-limit.mjs
```

Expected: FAIL because the global screen still uses the hardcoded export and detail screens discard backend errors.

- [ ] **Step 3: Use the live limit on the global screen**

Resolve:

```ts
const { data: participationPolicy } = useChallengeParticipationPolicy();
const maxActiveChallenges =
  participationPolicy?.maxActiveChallenges ?? DEFAULT_MAX_ACTIVE_CHALLENGES;
```

Use `maxActiveChallenges` for both the pre-check and dialog copy.

- [ ] **Step 4: Preserve backend messages on detail screens**

Change each join callback to:

```ts
onError: (error) => {
  Alert.alert('Could Not Join', error.message || 'Failed to join the challenge. Please try again.');
},
```

The shared mutation has already converted Axios response payloads into safe `Error` messages.

- [ ] **Step 5: Run the frontend regression and lint**

Run:

```bash
node scripts/test-active-challenge-limit.mjs
npx expo lint
```

Expected: regression PASS; lint exits successfully without new errors.

- [ ] **Step 6: Refresh the frontend graph**

Run:

```bash
graphify update .
```

Expected: graph update completes successfully.

### Task 5: Final Cross-Stack Verification

**Files:**
- Verify all modified backend and frontend files.

- [ ] **Step 1: Run backend focused and full tests**

Run:

```bash
./mvnw -Dtest=ChallengeParticipationLimitTest,ChallengeParticipationPolicyControllerTest,InviteSecurityRegressionTest test
./mvnw test
```

Expected: all tests PASS.

- [ ] **Step 2: Run frontend checks**

Run:

```bash
node scripts/test-active-challenge-limit.mjs
npx tsc --noEmit
```

Expected: both commands PASS.

- [ ] **Step 3: Review diffs without disturbing unrelated work**

Run scoped diffs for only the files named in this plan. Confirm every changed line belongs to configuration, enforcement, policy retrieval, or user-facing limit errors. Do not stage or revert unrelated dirty files.

- [ ] **Step 4: Commit backend and frontend changes separately**

Backend:

```bash
git add src/main/java/com/ironside/challengesphere/config/ChallengeParticipationProperties.java \
  src/main/java/com/ironside/challengesphere/api/response/ChallengeParticipationPolicyResponse.java \
  src/main/java/com/ironside/challengesphere/repository/UserRepository.java \
  src/main/java/com/ironside/challengesphere/repository/UserChallengeRepository.java \
  src/main/java/com/ironside/challengesphere/service/impl/ChallengeServiceImpl.java \
  src/main/java/com/ironside/challengesphere/controller/ChallengeController.java \
  src/main/resources/application.properties \
  src/main/resources/application-prod.properties \
  src/main/resources/application-local.properties \
  src/test/java/com/ironside/challengesphere/service/impl/ChallengeParticipationLimitTest.java \
  src/test/java/com/ironside/challengesphere/controller/ChallengeParticipationPolicyControllerTest.java
git commit -m "feat: cap active challenge participations"
```

Frontend:

```bash
git add types/api.ts services/challengeService.ts hooks/useChallenges.ts \
  'app/(tabs)/global.tsx' app/challenge-details.tsx app/global-challenge-details.tsx \
  scripts/test-active-challenge-limit.mjs graphify-out
git commit -m "feat: use configurable challenge participation limit"
```
