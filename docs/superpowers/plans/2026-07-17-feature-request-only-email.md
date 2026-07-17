# Feature-Request-Only Email Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make feature requests the only backend path that can send email, always to `ironside-dev@challengesphere.com`, while preserving in-app and push notifications.

**Architecture:** Replace the general-purpose `EmailService` with a dedicated feature-request capability that has no recipient parameter. Remove all notification and authentication email dependencies, disable email-only support ticket operations with `410 Gone`, and retain the existing SMTP and AWS SSM configuration solely for feature-request delivery.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Mail, Jakarta Mail, JUnit 5, Mockito, Maven, AWS EC2/SSM/S3.

## Global Constraints

- The only permitted outbound recipient is `ironside-dev@challengesphere.com`.
- No application caller may provide or override an email recipient.
- In-app notifications and push notifications must remain unchanged.
- Password authentication remains disabled in favor of Google Sign-In.
- Support ticket submission and support screenshot presigning must return `410 Gone` instead of claiming delivery.
- SMTP credentials remain in AWS SSM and must not be added to source or properties files.
- Do not modify unrelated tracked or untracked workspace changes.
- Do not use an em dash in user-facing copy or documentation.

---

### Task 1: Introduce the feature-request-only email capability

**Files:**
- Modify: `src/main/java/com/ironside/challengesphere/service/EmailService.java`
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/EmailServiceImpl.java`
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/FeatureRequestServiceImpl.java`
- Modify: `src/test/java/com/ironside/challengesphere/service/impl/FeatureRequestServiceImplTest.java`
- Create: `src/test/java/com/ironside/challengesphere/service/impl/EmailServiceImplTest.java`

**Interfaces:**
- Produces: `void EmailService.sendFeatureRequestEmail(String subject, String html) throws ChallengeSphereException`
- Guarantees: The recipient is fixed inside `EmailServiceImpl` and cannot be supplied by callers.
- Sequencing: Keep legacy email methods temporarily so the project compiles while Tasks 2 through 4 remove their callers. Task 5 deletes the legacy methods and implementations.

- [ ] **Step 1: Write failing tests for the narrow capability**

Update the feature-request verification to remove the recipient argument:

```java
verify(emailService).sendFeatureRequestEmail(
        eq("[ChallengeSphere Feature Request] CHALLENGES from @pranjal"),
        contains("Add team challenges")
);
```

Add `EmailServiceImplTest` using a real `MimeMessage` and mocked sender:

```java
class EmailServiceImplTest {
    @Test
    void featureRequestEmailAlwaysTargetsIronsideDevelopmentInbox() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "sender@example.com");
        ReflectionTestUtils.setField(service, "mailEnabled", true);

        service.sendFeatureRequestEmail("Feature request", "<p>Requested feature</p>");

        assertEquals(
                "ironside-dev@challengesphere.com",
                ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress()
        );
        verify(mailSender).send(message);
    }
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

Run:

```bash
./mvnw -Dtest=FeatureRequestServiceImplTest,EmailServiceImplTest test
```

Expected: compilation fails because `sendFeatureRequestEmail` does not exist.

- [ ] **Step 3: Add the dedicated email interface method**

Add this method to `EmailService` without deleting the legacy methods yet:

```java
void sendFeatureRequestEmail(String subject, String html) throws ChallengeSphereException;
```

Add the required path to `EmailServiceImpl`. Keep its existing exception behavior and use this fixed recipient:

```java
private static final String FEATURE_REQUEST_RECIPIENT =
        "ironside-dev@challengesphere.com";

@Override
public void sendFeatureRequestEmail(String subject, String html)
        throws ChallengeSphereException {
    if (!mailEnabled) {
        throw new ChallengeSphereException(
                "Email delivery is not configured",
                HttpStatus.SERVICE_UNAVAILABLE,
                logger
        );
    }
    try {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(FEATURE_REQUEST_RECIPIENT);
        helper.setSubject(subject);
        helper.setText(html, true);
        mailSender.send(message);
        logger.info("Sent feature request email");
    } catch (MessagingException | MailException e) {
        throw new ChallengeSphereException(
                "Failed to send feature request",
                HttpStatus.BAD_GATEWAY,
                logger,
                e
        );
    }
}
```

Do not remove the legacy methods from `EmailService` or `EmailServiceImpl` in this task. They remain only as a compile-safe transition until their callers are removed in Tasks 2 through 4.

Change `FeatureRequestServiceImpl` to:

```java
emailService.sendFeatureRequestEmail(
        buildEmailSubject(saved, user),
        buildEmailHtml(saved, user)
);
```

Remove its `SUPPORT_EMAIL` constant.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run:

```bash
./mvnw -Dtest=FeatureRequestServiceImplTest,EmailServiceImplTest test
```

Expected: all feature-request email tests pass.

- [ ] **Step 5: Commit the capability boundary**

```bash
git add src/main/java/com/ironside/challengesphere/service/EmailService.java \
  src/main/java/com/ironside/challengesphere/service/impl/EmailServiceImpl.java \
  src/main/java/com/ironside/challengesphere/service/impl/FeatureRequestServiceImpl.java \
  src/test/java/com/ironside/challengesphere/service/impl/FeatureRequestServiceImplTest.java \
  src/test/java/com/ironside/challengesphere/service/impl/EmailServiceImplTest.java
git commit -m "refactor: restrict email to feature requests"
```

---

### Task 2: Make notification delivery push and inbox only

**Files:**
- Modify: `src/main/java/com/ironside/challengesphere/service/NotificationService.java`
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java`
- Modify: `src/test/java/com/ironside/challengesphere/service/impl/NotificationServiceImplTest.java`
- Modify: `src/test/java/com/ironside/challengesphere/service/impl/NotificationInboxDeliverySeparationTest.java`

**Interfaces:**
- Consumes: Existing `PushNotificationService.sendInteractionPush(...)` behavior.
- Produces: Notification service constructors and delivery methods with no email dependency.

- [ ] **Step 1: Write failing notification tests that expose the new constructor**

Remove `EmailService` imports and the email mock from both notification test constructors:

```java
service = new NotificationServiceImpl(
        notificationRepository,
        userRepository,
        mock(ChallengeRepository.class),
        mock(FollowRepository.class),
        userBlockRepository,
        preferencesRepository,
        pushNotificationService
);
```

Retain the existing assertions that inbox records are saved and push calls are made or suppressed according to push preferences.

- [ ] **Step 2: Run tests and verify constructor compilation fails**

Run:

```bash
./mvnw -Dtest=NotificationServiceImplTest,NotificationInboxDeliverySeparationTest test
```

Expected: compilation fails because the current constructor still requires `EmailService`.

- [ ] **Step 3: Remove all notification email behavior**

In `NotificationService`, delete the unused method:

```java
Notification createAndEmail(Long userId, String type, String message, String actionObject);
```

In `NotificationServiceImpl`:

- Remove `EMAIL_NOTIFICATIONS_BIT` if it is no longer referenced.
- Remove the `EmailService` field, import, constructor parameter, and assignment.
- Remove `createAndEmail(...)`.
- Remove `sendEmailIfAllowed(...)` and `allowsEmail(...)`.
- Remove every call to `sendEmailIfAllowed(...)`.
- Leave `deliver(...)` ending with `return saved;` after inbox and push processing.
- Do not change `allowsInbox(...)`, `allowsPush(...)`, `allowsType(...)`, or push dispatch timing.

The final delivery boundary must be:

```java
private Notification deliver(User recipient,
                             UserPreferences prefs,
                             String type,
                             String message,
                             String actionObject) {
    Notification saved = null;
    if (allowsInbox(recipient)) {
        saved = notificationRepository.save(
                buildNotification(recipient.getId(), type, message, actionObject));
        if (allowsPush(recipient, prefs, type)) {
            dispatchPushAfterCommit(recipient.getId(), type, message, actionObject);
        }
    }
    return saved;
}
```

- [ ] **Step 4: Run focused notification tests**

Run:

```bash
./mvnw -Dtest=NotificationServiceImplTest,NotificationInboxDeliverySeparationTest,PushNotificationServiceImplTest test
```

Expected: constructor compilation succeeds and push/inbox tests pass. Record any pre-existing semantic assertion separately.

- [ ] **Step 5: Commit push-only notification delivery**

```bash
git add src/main/java/com/ironside/challengesphere/service/NotificationService.java \
  src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java \
  src/test/java/com/ironside/challengesphere/service/impl/NotificationServiceImplTest.java \
  src/test/java/com/ironside/challengesphere/service/impl/NotificationInboxDeliverySeparationTest.java
git commit -m "refactor: keep app notifications push only"
```

---

### Task 3: Remove dormant authentication and digest email paths

**Files:**
- Modify: `src/main/java/com/ironside/challengesphere/service/AuthService.java`
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/AuthServiceImpl.java`
- Modify: `src/main/java/com/ironside/challengesphere/controller/AuthController.java`
- Delete: `src/main/java/com/ironside/challengesphere/scheduler/NotificationDigestScheduler.java`

**Interfaces:**
- Produces: Google-only auth services with no email dependency.
- Preserves: Existing `410 Gone` behavior from disabled password endpoints.

- [ ] **Step 1: Add a source-level failing audit**

Run before implementation:

```bash
rg -n "sendWelcomeEmail|sendPasswordResetEmail|sendNotificationDigest|requestPasswordReset" \
  src/main/java
```

Expected: matches appear in authentication, email, and digest code.

- [ ] **Step 2: Remove authentication email dependencies**

Delete `requestPasswordReset(String email)` from `AuthService` and `AuthServiceImpl`.

Remove from `AuthServiceImpl`:

```java
import com.ironside.challengesphere.service.EmailService;
private final EmailService emailService;
```

Remove the `EmailService emailService` constructor parameter and assignment. Preserve the existing Google login, token refresh, and disabled password endpoint behavior.

Remove the unused `EmailService` field, import, constructor parameter, and assignment from `AuthController`.

Delete `NotificationDigestScheduler.java`. Digest email has no allowed recipient and must not remain as a dormant scheduled path.

- [ ] **Step 3: Run authentication and context compilation tests**

Run:

```bash
./mvnw -DskipTests compile
./mvnw -Dtest=UserServiceImplTest test
```

Expected: compilation succeeds and existing user-service tests pass.

- [ ] **Step 4: Verify forbidden auth and digest symbols are gone**

Run:

```bash
if rg -n "sendWelcomeEmail|sendPasswordResetEmail|sendNotificationDigest|requestPasswordReset" \
  src/main/java; then exit 1; fi
```

Expected: exit code 0 with no matches.

- [ ] **Step 5: Commit the dormant path removal**

```bash
git add src/main/java/com/ironside/challengesphere/service/AuthService.java \
  src/main/java/com/ironside/challengesphere/service/impl/AuthServiceImpl.java \
  src/main/java/com/ironside/challengesphere/controller/AuthController.java \
  src/main/java/com/ironside/challengesphere/scheduler/NotificationDigestScheduler.java
git commit -m "refactor: remove dormant user email paths"
```

---

### Task 4: Disable email-only support operations

**Files:**
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/SupportServiceImpl.java`
- Create: `src/test/java/com/ironside/challengesphere/service/impl/SupportServiceDisabledTest.java`

**Interfaces:**
- Produces: Both `SupportService.generatePresignedUrls(...)` and `SupportService.createTicket(...)` throw `ChallengeSphereException` with `HttpStatus.GONE`.
- Preserves: `/api/v1/support/feature-requests` through the separate feature-request service.

- [ ] **Step 1: Write failing support tests**

Construct `SupportServiceImpl` with its existing non-email dependencies and assert both operations fail before side effects:

```java
@Test
void supportTicketEmailPathIsGone() {
    ChallengeSphereException error = assertThrows(
            ChallengeSphereException.class,
            () -> service.createTicket(7L, new CreateSupportTicketRequest())
    );
    assertEquals(HttpStatus.GONE, error.getStatus());
    verifyNoInteractions(userRepository, uploadQuotaService, issuedUploadService);
}

@Test
void supportScreenshotUploadPathIsGone() {
    ChallengeSphereException error = assertThrows(
            ChallengeSphereException.class,
            () -> service.generatePresignedUrls(7L, new GeneratePresignedUrlsRequest())
    );
    assertEquals(HttpStatus.GONE, error.getStatus());
    verifyNoInteractions(userRepository, uploadQuotaService, issuedUploadService);
}
```

- [ ] **Step 2: Run the support test and verify it fails**

Run:

```bash
./mvnw -Dtest=SupportServiceDisabledTest test
```

Expected: tests fail because the current service validates or sends support requests.

- [ ] **Step 3: Replace support operation bodies with explicit removal responses**

Remove `EmailService` from `SupportServiceImpl` imports, fields, constructor parameters, and assignments. Remove `SUPPORT_EMAIL`.

Replace `generatePresignedUrls(...)` with:

```java
throw supportGone();
```

Replace `createTicket(...)` with:

```java
throw supportGone();
```

Add:

```java
private ChallengeSphereException supportGone() {
    return new ChallengeSphereException(
            "Support tickets are unavailable. Please use feature requests.",
            HttpStatus.GONE,
            logger
    );
}
```

Delete private code that becomes unreachable or unused after these two operations are removed, including email HTML building and support screenshot upload helpers. Keep only constructor dependencies and code still required by the two explicit `410 Gone` methods. If no dependencies remain, reduce the constructor to a no-argument Spring service.

- [ ] **Step 4: Run support and feature-request tests**

Run:

```bash
./mvnw -Dtest=SupportServiceDisabledTest,FeatureRequestServiceImplTest test
```

Expected: support operations return `410 Gone` and feature requests still pass.

- [ ] **Step 5: Commit support route shutdown**

```bash
git add src/main/java/com/ironside/challengesphere/service/impl/SupportServiceImpl.java \
  src/test/java/com/ironside/challengesphere/service/impl/SupportServiceDisabledTest.java
git commit -m "refactor: disable email-only support tickets"
```

---

### Task 5: Run the outbound email safety audit

**Files:**
- Review: `src/main/java`
- Review: `src/main/resources/application-prod.properties`
- Test: all focused test files from Tasks 1 through 4

**Interfaces:**
- Consumes: All preceding task outputs.
- Produces: A deployable JAR with one outbound email method and one fixed recipient.

- [ ] **Step 1: Delete the transitional general email API**

After Tasks 2 through 4 have removed all legacy callers, replace `EmailService` with:

```java
public interface EmailService {
    void sendFeatureRequestEmail(String subject, String html) throws ChallengeSphereException;
}
```

Delete from `EmailServiceImpl` all welcome, password-reset, digest, plain-email, arbitrary-recipient, and frontend URL code. Keep only the fixed-recipient feature-request implementation from Task 1.

- [ ] **Step 2: Audit all email symbols and recipients**

Run:

```bash
rg -n "EmailService|JavaMailSender|MimeMessageHelper|send.*Email|sendPlainEmail|sendHtmlEmail" \
  src/main/java
rg -n "@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}" src/main/java | rg -i "mail|email|recipient|support"
```

Expected: production email references exist only in `EmailService`, `EmailServiceImpl`, and `FeatureRequestServiceImpl`; the only fixed recipient is `ironside-dev@challengesphere.com`.

- [ ] **Step 3: Run all focused tests**

Run:

```bash
./mvnw -Dtest=EmailServiceImplTest,FeatureRequestServiceImplTest,SupportServiceDisabledTest,NotificationServiceImplTest,NotificationInboxDeliverySeparationTest,PushNotificationServiceImplTest test
```

Expected: all tests relevant to this change pass.

- [ ] **Step 4: Run the full backend suite**

Run:

```bash
./mvnw test
```

Expected: no new failure categories. Record the existing baseline failures separately if they remain.

- [ ] **Step 5: Build and inspect the release artifact**

Run:

```bash
./mvnw -DskipTests package
shasum -a 256 target/challengesphere-0.0.1-SNAPSHOT.jar
git diff --check
```

Expected: package succeeds, a SHA-256 checksum is printed, and `git diff --check` has no output.

- [ ] **Step 6: Commit the final email boundary and any audit corrections**

Commit the final interface reduction, implementation cleanup, and any required test corrections:

```bash
git add src/main/java/com/ironside/challengesphere/service/EmailService.java \
  src/main/java/com/ironside/challengesphere/service/impl/EmailServiceImpl.java \
  src/test/java/com/ironside/challengesphere
git commit -m "refactor: enforce feature-request-only email policy"
```

---

### Task 6: Deploy and verify production

**Files:**
- Deploy: `target/challengesphere-0.0.1-SNAPSHOT.jar`
- Preserve: `/opt/challengesphere/challengesphere.jar`
- Preserve: `/etc/challengesphere/challengesphere.env`

**Interfaces:**
- Consumes: The checksum-verified JAR from Task 5.
- Produces: Healthy production service with feature-request-only email delivery.

- [ ] **Step 1: Verify production preconditions**

Use AWS SSM to confirm:

```text
systemctl is-active challengesphere.service
curl -fsS http://127.0.0.1:8080/actuator/health
sha256sum /opt/challengesphere/challengesphere.jar
```

Expected: service is active and health reports `UP` before deployment.

- [ ] **Step 2: Upload the candidate artifact securely**

Upload the JAR to an encrypted temporary S3 deployment key, generate a short-lived presigned URL, and verify the S3 object size. Do not store credentials in the object or command output.

- [ ] **Step 3: Deploy through SSM with automatic rollback**

The remote script must:

1. Download to `/opt/challengesphere/challengesphere.jar.candidate`.
2. Verify the candidate SHA-256 checksum.
3. Copy the current JAR to a checksum-named rollback file.
4. Install the candidate.
5. Restart `challengesphere.service`.
6. Poll `/actuator/health` for up to 120 seconds.
7. Restore the previous JAR and restart if health never becomes `UP`.

- [ ] **Step 4: Independently verify the live service**

Use a separate SSM command to confirm:

```text
live JAR SHA equals local candidate SHA
systemctl is-active returns active
/actuator/health returns {"status":"UP"}
recent startup logs contain no mail or application startup errors
```

Run the source audit again locally and confirm the fixed recipient remains the only outbound target.

- [ ] **Step 5: Clean temporary deployment artifacts**

Delete the temporary S3 JAR, verify the object is gone, and retain the EC2 rollback JAR. Do not remove the encrypted SMTP parameter because feature-request delivery still requires it.

- [ ] **Step 6: Report exact results**

Report:

- Deployment checksum
- Service and health status
- Focused test counts
- Full-suite baseline failures, if any
- Confirmation that all app-user email paths are removed
- Confirmation that feature requests target only `ironside-dev@challengesphere.com`
