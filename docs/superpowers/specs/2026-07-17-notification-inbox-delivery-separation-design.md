# Notification Inbox and Push Delivery Separation

## Goal

Ensure that every eligible follow, comment, and positive Glory interaction is recorded in the recipient's bell inbox, while phone push alerts continue to respect the recipient's notification preferences. Preserve the working 7 PM upload reminder without changing its scheduling or delivery path.

## Current Problem

`NotificationServiceImpl` uses one `allowsInApp` check for two separate decisions:

1. Whether to persist an in-app notification row.
2. Whether to send a phone push alert.

That check includes legacy push state, the global push preference, and per-event preferences. Consequently, disabling push alerts also prevents the event from appearing in the bell inbox.

There is also a default mismatch for Glory alerts: the database migration defaults `notify_on_glory` to `TRUE`, but the Java `UserPreferences` field initializes it to `false`. Preferences created through JPA can therefore disable new-user Glory pushes unexpectedly.

## Required Behavior

- A follow creates a bell entry for the followed user.
- A comment creates a bell entry for the submission owner.
- Giving a Glory creates a bell entry for the submission owner.
- Removing a Glory does not create a notification.
- Re-giving a Glory after removal is treated as a new positive interaction and creates a new entry.
- Users never receive notifications for their own follow, comment, or Glory actions.
- Active blocks continue to suppress interaction notifications.
- Bell entries are persisted regardless of global phone-push or per-event push preferences.
- Phone push alerts continue to honor legacy notification state, global push state, and per-event preferences.
- New preference records default Glory push alerts to enabled.
- Existing explicit preference values are preserved.
- The 7 PM upload reminder implementation remains unchanged.

## Design

### Backend notification delivery

Replace the combined in-app/push decision with separate responsibilities:

- `allowsInbox(...)` determines whether an eligible event may be stored. For the interaction types in scope, eligibility is based on a valid active recipient and the existing self-action and block checks, not phone-push preferences.
- `allowsPush(...)` retains the current legacy notification mask, global push preference, and per-type preference checks.
- `deliver(...)` persists the bell entry first. After the surrounding transaction commits, it dispatches a push only when `allowsPush(...)` is true.
- Email behavior remains separate and continues to honor email and per-type preferences.

Bulk notification paths should use the same separation so the shared service does not retain two meanings for one preference check. The implementation will stay focused on notification delivery and avoid unrelated refactoring.

### Preference defaults

Change the Java default for `UserPreferences.notifyOnGlory` to `true`, matching the existing database default. Do not rewrite existing rows, because a stored `false` may represent a deliberate user choice for phone alerts.

### Frontend bell

No bell layout change is required. The existing notifications screen already renders and routes `FOLLOW`, `COMMENT`, and `GLORY` entries, and the My Challenges page already displays the bell and unread count. Once the backend persists all eligible interactions, they will appear through the existing notifications API.

The existing foreground push listener and unread-count polling remain responsible for refreshing the badge. No changes are required to the working 7 PM notification route.

## Error Handling and Transaction Boundaries

- Persist notification rows inside the notification transaction.
- Dispatch phone pushes only after a successful commit, preserving the current after-commit pattern.
- A push delivery failure must not roll back or remove the bell entry.
- Existing caller-side exception handling remains in place so a notification failure does not fail the underlying follow, comment, or Glory action.

## Tests

Add backend regression coverage for:

- A follow persists an inbox row and sends an allowed push.
- A comment persists an inbox row when global push is disabled and sends no push.
- A Glory persists an inbox row when its per-event push setting is disabled and sends no push.
- Self-actions and blocked-user interactions still persist nothing and send nothing.
- New `UserPreferences` instances default Glory notifications to enabled.
- The existing push-service reminder tests continue to pass, demonstrating that the 7 PM path is unaffected.

Run the focused notification tests first, then the complete backend test suite. Run the existing frontend notification routing and unread-count checks to confirm that the API contract and bell behavior remain compatible.

## Out of Scope

- Redesigning the notifications screen.
- Changing notification retention, pagination, or aggregation.
- Forcing phone alerts when device permissions or user preferences disable them.
- Sending notifications for Glory removal or self-interactions.
- Changing the 7 PM reminder schedule or content.
