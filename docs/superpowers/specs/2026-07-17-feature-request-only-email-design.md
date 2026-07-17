# Feature-Request-Only Email Design

## Goal

ChallengeSphere must not send email to app users. User-facing activity must use in-app notifications and push notifications wherever those channels already apply. The only permitted email is an internal feature-request message sent to `ironside-dev@challengesphere.com`.

## Email Policy

The backend will expose one narrow email capability for feature requests. Callers will provide only the subject and HTML body. They will not provide a recipient. The implementation will always address the message to the fixed internal recipient `ironside-dev@challengesphere.com`.

The general-purpose email methods will be removed so notification, authentication, support, or future application code cannot send email to arbitrary recipients through the normal service interface.

SMTP remains enabled in production because the approved feature-request path still requires it. The existing encrypted SMTP credential flow remains unchanged.

## Notification Behavior

Notification records in the app remain unchanged. Push notifications remain unchanged and continue to honor their existing notification preferences.

Email delivery will be removed from:

- Follow notifications
- Comment notifications
- Glory notifications
- Submission notifications
- Challenge invitations and updates
- Notification digests
- Welcome messages
- Authentication and password-reset services

The existing `email_notifications_enabled` database field can remain for backward compatibility, but the backend will no longer consult it or send user notification email.

## Feature Requests

`FeatureRequestServiceImpl` will remain the only caller of the email capability. It will:

1. Validate and rate-limit the request.
2. Store the feature request in the database.
3. Send one HTML email to `ironside-dev@challengesphere.com`.
4. Return the existing feature-request response.

Email failure remains fail-closed for this path. The API will report that the feature request could not be delivered rather than claiming success when the internal email was not sent.

## Authentication

Password registration, login, forgot-password, reset-password, and change-password endpoints already return the Google-only authentication response. The unused password-reset email call will be removed from the service implementation so no internal code path can send it later by accident.

## Support Tickets

Support tickets currently have no durable ticket store and depend entirely on email delivery. Removing their email call while returning success would lose user reports silently.

The support-ticket submission endpoint and support screenshot presigning endpoint will therefore return `410 Gone` with a clear message directing users to the feature-request flow. This prevents orphaned screenshot uploads and false delivery confirmations.

## Safety Boundaries

- No email method will accept a recipient from application code.
- The sole recipient is fixed to `ironside-dev@challengesphere.com`.
- No user email address will be used as an outbound recipient.
- SMTP secrets remain in AWS SSM and are not stored in source code or application properties.
- Push and in-app notification behavior will not be refactored as part of this change.

## Testing

Focused tests will prove:

- Feature requests send exactly one message through the dedicated capability.
- The feature-request caller cannot select or override the recipient.
- Follow, comment, glory, submission, and challenge-update delivery does not invoke email.
- Notification push behavior remains intact.
- Digest scheduling does not send email.
- Support ticket and screenshot endpoints return `410 Gone` without sending email or issuing upload keys.
- Google-only authentication routes remain unchanged.

The full backend test suite will also be run. Existing unrelated baseline failures will be reported separately from the focused tests for this change.

## Deployment Verification

Production verification will confirm:

- The deployed JAR checksum matches the tested artifact.
- The service is active and `/actuator/health` reports `UP`.
- Feature-request email is configured for `ironside-dev@challengesphere.com`.
- No general user-email call sites remain in the application code.
- Recent startup logs contain no mail configuration or application startup errors.
