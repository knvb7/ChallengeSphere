# Authentication Production Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore Google sign-in, password reset, and account creation behavior against the ap-south-1 production stack.

**Architecture:** Keep duplicate account protection in the backend, but normalize API errors in the frontend so users see actionable messages. Deploy the latest tested backend to the active EC2 node, point its runtime at the ap-south-1 Aurora cluster, and apply only idempotent migrations proven missing by a schema audit.

**Tech Stack:** Expo/React Native, TypeScript, Spring Boot, PostgreSQL/Aurora, EC2/systemd, AWS RDS IAM authentication

---

### Task 1: Frontend authentication errors

**Files:**
- Create: `../ChallengeSphere-Frontend/utils/apiError.ts`
- Modify: `../ChallengeSphere-Frontend/app/signup.tsx`
- Modify: `../ChallengeSphere-Frontend/app/login.tsx`
- Modify: `../ChallengeSphere-Frontend/app/forgot-password.tsx`
- Test: `../ChallengeSphere-Frontend/scripts/test-auth-api-errors.mjs`

- [ ] Write a failing runtime test covering backend `errorMessage`, conventional `message`, generic Axios status text, and fallback behavior.
- [ ] Run the test and confirm it fails because `utils/apiError.ts` does not exist.
- [ ] Implement `getApiErrorMessage` and use it in the signup, login, Google, and forgot-password error paths.
- [ ] Run the targeted authentication scripts and TypeScript compiler.

### Task 2: Backend authentication behavior

**Files:**
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/AuthServiceImpl.java`
- Modify: `src/main/java/com/ironside/challengesphere/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/ironside/challengesphere/service/impl/AuthServiceImplTest.java`
- Test: `src/test/java/com/ironside/challengesphere/AuthEndpointsRegressionTest.java`

- [ ] Add failing tests for malformed Google credentials returning a controlled auth error and password reset request behavior.
- [ ] Run the targeted Maven tests and confirm the new assertions fail for the intended reason.
- [ ] Catch malformed Google token input inside the auth service and preserve the generic forgot-password response.
- [ ] Run targeted and full backend tests.

### Task 3: Aurora migration audit

**Files:**
- Inspect: `src/main/resources/db/migration/V11__google_signin.sql`
- Inspect: `src/main/resources/db/migration/V13__auth_tokens.sql`
- Inspect: `src/main/resources/db/migration/V24__add_user_roles.sql`

- [ ] Establish an authenticated connection to the ap-south-1 Aurora writer.
- [ ] Query `information_schema` for `google_id`, nullable password, `auth_tokens`, and `role`.
- [ ] Apply only missing idempotent statements inside a transaction with `ON_ERROR_STOP`.
- [ ] Repeat the schema audit and record the result.

### Task 4: Production deployment and smoke tests

**Files:**
- Deploy: `target/challengesphere-0.0.1-SNAPSHOT.jar`
- Modify remotely: `/etc/challengesphere/challengesphere.env`

- [ ] Build the backend JAR from the verified workspace.
- [ ] Back up the remote JAR and environment file.
- [ ] Change the active node's database URL, IAM hostname, and IAM region to ap-south-1.
- [ ] Upload the new JAR, restart `challengesphere.service`, and verify startup logs and health.
- [ ] Smoke-test username availability, forgot password with a nonexistent address, duplicate registration messaging, malformed Google credentials, and normal login behavior.
- [ ] Confirm logs generate RDS IAM tokens for ap-south-1 and contain no repeated graceful shutdown loop.

