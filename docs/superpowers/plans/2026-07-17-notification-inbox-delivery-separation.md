# Notification Inbox and Push Delivery Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist every eligible follow, comment, and positive Glory in the bell inbox while allowing phone push preferences to control only phone alerts.

**Architecture:** Split the existing combined notification gate into an inbox-eligibility check and a phone-push preference check. Persist notification rows inside the transaction, dispatch allowed phone pushes after commit, and leave the independent 7 PM reminder path unchanged.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, JUnit 5, Mockito, Maven, Expo/React Native regression scripts.

## Global Constraints

- Preserve the working 7 PM upload reminder scheduler and delivery path without modification.
- Persist eligible follow, comment, and positive Glory interactions in the bell regardless of phone-push preferences.
- Continue honoring legacy notification state, global push state, and per-event settings for phone alerts.
- Preserve existing self-action and active-block suppression.
- Do not create notifications when a Glory is removed.
- Preserve existing explicit preference values; do not migrate stored `false` values.
- Do not redesign the notifications screen or change its API contract.
- Preserve all unrelated pre-existing workspace changes.

---

## File Map

- Create `src/test/java/com/ironside/challengesphere/service/impl/NotificationInboxDeliverySeparationTest.java`: focused regression coverage for independent inbox persistence and phone-push suppression.
- Modify `src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java`: separate inbox eligibility from phone-push preferences in single-recipient and bulk notification delivery.
- Create `src/test/java/com/ironside/challengesphere/model/UserPreferencesDefaultsTest.java`: locks the expected new-preference Glory default.
- Modify `src/main/java/com/ironside/challengesphere/model/UserPreferences.java`: align the Java Glory preference default with the database default.

### Task 1: Separate bell persistence from phone push preferences

**Files:**
- Create: `src/test/java/com/ironside/challengesphere/service/impl/NotificationInboxDeliverySeparationTest.java`
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java`

**Interfaces:**
- Consumes: `NotificationServiceImpl.notifyComment(Submission, Long, Long)`, `NotificationServiceImpl.notifyGlory(Submission, Long)`, `NotificationRepository.save(Notification)`, and `PushNotificationService.sendInteractionPush(Long, String, String, String)`.
- Produces: private `allowsInbox(User): boolean` and `allowsPush(User, UserPreferences, String): boolean` decision boundaries used by `deliver(...)` and all bulk delivery loops.

- [ ] **Step 1: Write focused failing regression tests**

Create `NotificationInboxDeliverySeparationTest.java` with the following content:

```java
package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserPreferencesRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.EmailService;
import com.ironside.challengesphere.service.PushNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationInboxDeliverySeparationTest {

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private UserPreferencesRepository preferencesRepository;
    private PushNotificationService pushNotificationService;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        preferencesRepository = mock(UserPreferencesRepository.class);
        pushNotificationService = mock(PushNotificationService.class);
        service = new NotificationServiceImpl(
                notificationRepository,
                userRepository,
                mock(ChallengeRepository.class),
                mock(FollowRepository.class),
                mock(UserBlockRepository.class),
                preferencesRepository,
                mock(EmailService.class),
                pushNotificationService
        );
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void commentPersistsInInboxWhenGlobalPhonePushIsDisabled() {
        Submission submission = submissionOwnedBy(1L);
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        UserPreferences preferences = preferencesFor(1L);
        preferences.setPushNotificationsEnabled(false);
        preferences.setNotifyOnComment(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserId(1L)).thenReturn(Optional.of(preferences));

        Notification result = service.notifyComment(submission, 2L, 99L);

        assertNotNull(result);
        verify(notificationRepository).save(any(Notification.class));
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
    }

    @Test
    void gloryPersistsInInboxWhenGloryPhonePushIsDisabled() {
        Submission submission = submissionOwnedBy(1L);
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        UserPreferences preferences = preferencesFor(1L);
        preferences.setPushNotificationsEnabled(true);
        preferences.setNotifyOnGlory(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserId(1L)).thenReturn(Optional.of(preferences));

        Notification result = service.notifyGlory(submission, 2L);

        assertNotNull(result);
        verify(notificationRepository).save(any(Notification.class));
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
    }

    private Submission submissionOwnedBy(Long userId) {
        Submission submission = new Submission();
        submission.setId(9L);
        submission.setUserId(userId);
        submission.setChallengeId(3L);
        submission.setDayNumber(1);
        return submission;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setActive(true);
        user.setNotifications(1);
        return user;
    }

    private UserPreferences preferencesFor(Long userId) {
        UserPreferences preferences = new UserPreferences();
        preferences.setUserId(userId);
        preferences.setEmailNotificationsEnabled(false);
        preferences.setNotifyOnFollow(true);
        preferences.setNotifyOnComment(true);
        preferences.setNotifyOnGlory(true);
        return preferences;
    }
}
```

- [ ] **Step 2: Run the new tests and confirm the current coupling fails them**

Run:

```bash
./mvnw -Dtest=NotificationInboxDeliverySeparationTest test
```

Expected: both tests fail at `assertNotNull(result)` because the current `allowsInApp(...)` gate suppresses the inbox row together with the phone push.

- [ ] **Step 3: Split the notification gates in single-recipient delivery**

In `NotificationServiceImpl.java`, replace `deliver(...)`, `allowsInApp(...)`, and their logging with this structure:

```java
private Notification deliver(User recipient,
                             UserPreferences prefs,
                             String type,
                             String message,
                             String actionObject) {
    Notification saved = null;
    if (allowsInbox(recipient)) {
        saved = notificationRepository.save(buildNotification(recipient.getId(), type, message, actionObject));
        logger.info("Created notification id={} for userId={} type={}", saved.getId(), recipient.getId(), type);
        if (allowsPush(recipient, prefs, type)) {
            dispatchPushAfterCommit(recipient.getId(), type, message, actionObject);
        }
    } else {
        logger.debug("In-app notification suppressed for ineligible userId={} type={}",
                recipient == null ? null : recipient.getId(), type);
    }
    sendEmailIfAllowed(recipient, prefs, type, message);
    return saved;
}

private boolean allowsInbox(User user) {
    return user != null
            && user.getId() != null
            && !Boolean.FALSE.equals(user.getActive());
}

private boolean allowsPush(User user, UserPreferences prefs, String type) {
    if (!allowsInbox(user)) {
        return false;
    }
    Integer mask = user.getNotifications();
    boolean legacyPushEnabled = mask == null || (mask & PUSH_NOTIFICATIONS_BIT) == PUSH_NOTIFICATIONS_BIT;
    return legacyPushEnabled
            && (prefs == null || Boolean.TRUE.equals(prefs.getPushNotificationsEnabled()))
            && allowsType(prefs, type);
}
```

Keep `allowsEmail(...)` unchanged so email delivery still respects the email bit and per-event preferences.

- [ ] **Step 4: Apply the same split to bulk notification loops**

For each bulk loop in `notifySubmission(...)`, `notifyChallengeInvitesAsync(...)`, and `notifyChallengeJoined(...)`, replace the combined save-and-push block:

```java
if (allowsInApp(recipient, prefs, type)) {
    toSave.add(buildNotification(recipient.getId(), type, message, actionObject));
    pushes.add(new PushDispatch(recipient.getId(), type, message, actionObject));
}
```

with independent decisions using that method's concrete type constant:

```java
if (allowsInbox(recipient)) {
    toSave.add(buildNotification(recipient.getId(), type, message, actionObject));
    if (allowsPush(recipient, prefs, type)) {
        pushes.add(new PushDispatch(recipient.getId(), type, message, actionObject));
    }
}
```

Use `TYPE_SUBMISSION`, `TYPE_CHALLENGE_INVITE`, or `TYPE_CHALLENGE_UPDATE` in place of `type` at each call site. Preserve `allowsChallengeInvites(prefs)` for invite eligibility.

- [ ] **Step 5: Run focused notification tests**

Run:

```bash
./mvnw -Dtest=NotificationInboxDeliverySeparationTest,NotificationServiceImplTest test
```

Expected: all tests pass. The new tests verify inbox persistence without pushes; existing tests verify allowed follow pushes, bulk save-before-push ordering, self-action suppression, and block suppression.

- [ ] **Step 6: Inspect and commit only Task 1 files**

Run:

```bash
git diff --check -- src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java src/test/java/com/ironside/challengesphere/service/impl/NotificationInboxDeliverySeparationTest.java
git diff -- src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java src/test/java/com/ironside/challengesphere/service/impl/NotificationInboxDeliverySeparationTest.java
git add src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java src/test/java/com/ironside/challengesphere/service/impl/NotificationInboxDeliverySeparationTest.java
git commit -m "fix: keep interaction notifications in inbox"
```

Expected: the commit contains only the notification service and its new focused test.

### Task 2: Align the new-user Glory push default

**Files:**
- Create: `src/test/java/com/ironside/challengesphere/model/UserPreferencesDefaultsTest.java`
- Modify: `src/main/java/com/ironside/challengesphere/model/UserPreferences.java`

**Interfaces:**
- Consumes: the no-argument `UserPreferences()` constructor used by `UserPreferencesServiceImpl.createDefaults(Long)`.
- Produces: `UserPreferences.getNotifyOnGlory()` returning `true` for newly constructed preference records, matching migration `V3__add_user_preferences.sql`.

- [ ] **Step 1: Add a failing model-default test**

Create `UserPreferencesDefaultsTest.java`:

```java
package com.ironside.challengesphere.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPreferencesDefaultsTest {

    @Test
    void newPreferencesEnableGloryNotificationsByDefault() {
        UserPreferences preferences = new UserPreferences();

        assertTrue(preferences.getNotifyOnGlory());
    }
}
```

- [ ] **Step 2: Run the test and confirm the Java/database mismatch**

Run:

```bash
./mvnw -Dtest=UserPreferencesDefaultsTest test
```

Expected: FAIL because `notifyOnGlory` currently initializes to `false`.

- [ ] **Step 3: Make the Java default match the database default**

In `UserPreferences.java`, change only this field initializer:

```java
@Column(name = "notify_on_glory", nullable = false)
private Boolean notifyOnGlory = true;
```

Do not add a data migration or update existing rows.

- [ ] **Step 4: Run the focused default and notification tests**

Run:

```bash
./mvnw -Dtest=UserPreferencesDefaultsTest,NotificationInboxDeliverySeparationTest,NotificationServiceImplTest test
```

Expected: all tests pass.

- [ ] **Step 5: Inspect and commit only Task 2 files**

Run:

```bash
git diff --check -- src/main/java/com/ironside/challengesphere/model/UserPreferences.java src/test/java/com/ironside/challengesphere/model/UserPreferencesDefaultsTest.java
git diff -- src/main/java/com/ironside/challengesphere/model/UserPreferences.java src/test/java/com/ironside/challengesphere/model/UserPreferencesDefaultsTest.java
git add src/main/java/com/ironside/challengesphere/model/UserPreferences.java src/test/java/com/ironside/challengesphere/model/UserPreferencesDefaultsTest.java
git commit -m "fix: enable glory alerts for new preferences"
```

Expected: the commit contains only the preference model and its default test.

### Task 3: Verify the full notification contract and unchanged reminder behavior

**Files:**
- Verify only: backend and frontend test suites; no production file changes are expected.

**Interfaces:**
- Consumes: the backend notification persistence and push dispatch behavior from Tasks 1–2, plus the existing frontend notification API and routing contract.
- Produces: fresh evidence that interaction inbox entries, phone push delivery, the unread bell badge, notification routing, and the 7 PM reminder behavior remain compatible.

- [ ] **Step 1: Run all backend tests**

Run:

```bash
./mvnw test
```

Expected: Maven exits `0` with zero test failures and zero test errors.

- [ ] **Step 2: Run the explicit backend push/reminder tests**

Run:

```bash
./mvnw -Dtest=PushNotificationServiceImplTest,ExpoPushNotificationSenderTest test
```

Expected: all interaction-push and daily-reminder tests pass, including once-per-local-day reminder delivery.

- [ ] **Step 3: Run frontend notification regressions**

From `/Users/pranjaldalal/Documents/Startup/ChallengeSphere-Frontend`, run:

```bash
node scripts/test-notification-unread-count-throttle.mjs
npm run test:push-notifications
```

Expected: the unread-count script prints `notification unread count throttle checks passed`; the push suite exits `0` and includes `push notification routing checks passed`.

- [ ] **Step 4: Run backend compile/package verification without rerunning tests**

From `/Users/pranjaldalal/Documents/Startup/challengesphere`, run:

```bash
./mvnw -DskipTests package
```

Expected: Maven exits `0` with `BUILD SUCCESS`.

- [ ] **Step 5: Refresh the code graph if Graphify is available**

Run:

```bash
if command -v graphify >/dev/null 2>&1; then graphify update .; else echo "graphify unavailable; skipped graph refresh"; fi
```

Expected: Graphify completes successfully, or the command explicitly reports that it is unavailable. If Graphify modifies generated graph files, inspect and keep those generated updates separate from the feature commits.

- [ ] **Step 6: Audit final scope**

Run:

```bash
git status --short
git log -3 --oneline
git show --stat --oneline HEAD
git show --stat --oneline HEAD~1
```

Expected: the two implementation commits contain only the four planned source/test files. All unrelated pre-existing workspace changes remain present and uncommitted.
