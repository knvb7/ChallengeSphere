# Credential-Free SES Email Delivery Design

## Goal

Configure reliable backend email delivery without storing a user's mailbox
password, an SMTP password, or a long-lived AWS access key in the application,
on the EC2 instance, or in the deployable artifact.

The backend will send transactional email through the Amazon SES v2 API in
`ap-south-1` as `noreply@challengesphere.com`. The AWS SDK will authenticate
with the temporary credentials supplied and rotated by the EC2 instance role.

## Current Failure and Root Cause

The live production process starts with the `prod` Spring profile, but its
systemd environment explicitly sets `APP_MAIL_ENABLED=false`. It also has no
`MAIL_USERNAME` or `MAIL_PASSWORD`. `EmailServiceImpl.sendHtmlEmail(...)`
therefore throws `Email delivery is not configured` before attempting network
delivery.

The codebase currently implements delivery with Spring's `JavaMailSender` and
Gmail SMTP. Enabling that implementation would require a stored Gmail app
password, which does not meet the approved security requirement.

The AWS account's SES service in `ap-south-1` is enabled but remains in the
sandbox and has no verified identities. The `challengesphere.com` public Route
53 hosted zone is in the same AWS account, so domain verification records can
be managed without introducing a separate DNS provider or credential.

## Chosen Approach

Use the Amazon SES v2 HTTPS API through the AWS SDK for Java 2.x and the
existing EC2 instance profile. Do not use the SES SMTP interface. The SMTP
interface would replace the Gmail password with an SES SMTP password rather
than eliminate stored email credentials.

The runtime role receives only the `ses:SendEmail` permission required for the
verified `challengesphere.com` identity. The permission is further constrained
to `noreply@challengesphere.com` as the From address. The application receives
no permission to create or modify SES identities, policies, configuration
sets, or account settings.

## Application Architecture

The existing `EmailService` interface and all callers remain unchanged.
`EmailServiceImpl` continues to own the welcome, password-reset, notification
digest, support, plain-text, and HTML message composition behavior.

Delivery is separated behind a small `EmailDeliveryGateway` interface:

- `EmailServiceImpl` validates inputs, builds subjects and bodies, and chooses
  whether a delivery failure is logged or returned to the caller.
- `SesEmailDeliveryGateway` converts a delivery request to an SES v2
  `SendEmailRequest` and calls `SesV2Client.sendEmail(...)`.
- An immutable delivery request carries only the recipient, subject, optional
  plain-text body, and optional HTML body.
- Spring configuration creates one reusable `SesV2Client` for the configured
  region with the AWS SDK default credentials provider chain. No static
  credentials provider is permitted.

The SES simple-content API is sufficient because the current emails contain
text and HTML but no MIME attachments. Support screenshots remain links to the
existing short-lived readable URLs rather than email attachments.

The Gmail/JavaMail transport and its SMTP-specific dependencies and
configuration are removed when no other code uses them. This prevents an
accidental fallback to credential-based SMTP.

## Configuration

Production configuration uses non-secret values only:

- `APP_MAIL_ENABLED=true`
- `APP_MAIL_PROVIDER=ses`
- `APP_MAIL_FROM_ADDRESS=noreply@challengesphere.com`
- `APP_MAIL_SES_REGION=ap-south-1`
- `AWS_EC2_METADATA_V1_DISABLED=true`

The application validates the provider, From address, and region at startup
when email is enabled. A missing or unsupported value fails startup with a
specific configuration error instead of allowing the backend to accept email
work it cannot deliver.

Local development keeps email disabled by default. Unit tests inject a fake
gateway and never call AWS. Developers who explicitly enable SES locally use
their normal AWS SDK credential chain; the repository and local properties do
not contain credentials.

`MAIL_USERNAME`, `MAIL_PASSWORD`, Gmail host/port settings, and SMTP auth/TLS
settings are removed from the production runtime environment and active
application configuration.

## Delivery Behavior and Errors

Existing call-site semantics are preserved:

- Required synchronous email, currently used by support-ticket submission,
  propagates an SES rejection or transport failure as the existing
  `ChallengeSphereException` with HTTP `502 Bad Gateway`.
- Optional asynchronous welcome, password-reset, digest, and plain email
  delivery logs the failure and does not roll back the originating business
  operation.
- Disabled local delivery remains a no-op for optional messages. Required
  delivery reports `503 Service Unavailable`.

Logs include the delivery type, SES request ID when available, and exception
class. They do not include message bodies, password-reset codes, credentials,
or full recipient addresses.

The initial scope does not add retries. Retrying SES calls without a durable
outbox can create duplicate email after ambiguous network failures. Reliable
retry and dead-letter handling can be added later as a separate outbox design
if production metrics show that it is needed.

## AWS Infrastructure

The setup is performed in `ap-south-1`:

1. Create an SES domain identity for `challengesphere.com`.
2. Publish the SES DKIM CNAME records in the existing public Route 53 hosted
   zone and wait for verification.
3. Attach a least-privilege inline policy to
   `ChallengeSphereBackendSSMRole` allowing `ses:SendEmail` only against the
   `challengesphere.com` SES identity and only when the From address is
   `noreply@challengesphere.com`.
4. Request SES production access for transactional account, password-reset,
   notification, and support email. Until AWS approves the request, sandbox
   rules restrict delivery to verified recipients or the SES mailbox
   simulator.
5. Deploy the tested backend artifact, update the non-secret systemd
   environment, restart the service, and verify the health endpoint.

Identity and DNS creation are deployment infrastructure operations, not
runtime application permissions. They are performed using the authorized
operator AWS session and are not granted to the EC2 role.

## Testing and Verification

Implementation follows a red-green test cycle.

Unit tests cover:

- SES requests contain the configured From address, recipient, UTF-8 subject,
  and the expected text and/or HTML bodies.
- SES SDK failures are translated to the correct required-delivery exception.
- Optional asynchronous delivery contains failures without leaking sensitive
  message content to logs.
- Email-disabled behavior preserves the existing optional and required
  semantics.
- The SDK client configuration uses the default credentials chain and contains
  no static access key or secret.
- Production configuration contains no SMTP password, Gmail password, or
  static AWS credential setting.
- Existing template escaping and all `EmailService` call-site behavior remain
  covered.

Verification includes the targeted email tests, the complete Maven test suite,
and a production package build. After AWS identity verification, a live smoke
test runs from the EC2 role to the SES mailbox simulator. After production
access is approved, one end-to-end transactional email is sent to an ordinary
recipient and the corresponding backend log is checked for the SES request ID.

## Rollout and Rollback

The code and IAM policy deploy before `APP_MAIL_ENABLED` is set to `true`.
Email is enabled only after the SES identity is verified and the EC2 smoke test
succeeds.

If delivery causes production errors, rollback sets `APP_MAIL_ENABLED=false`
and restarts the service. The SES IAM statement can then be removed. This
rollback disables email-dependent support submission, matching current
behavior, while leaving unrelated backend features available.

## User-Side Actions

No mailbox password, SMTP password, AWS access key, or secret needs to be
provided.

AWS may ask the account owner to respond to the SES production-access case or
clarify the transactional-email use case. The user only needs to handle that
AWS correspondence if it occurs. All code, IAM, SES identity, Route 53 DNS,
deployment, and smoke-test work is otherwise within the approved backend fix.

## Out of Scope

- Marketing or bulk email campaigns
- Email attachments
- A durable email outbox, automatic retry, or dead-letter queue
- Inbound email processing
- Changing notification preference product behavior
- Redesigning the existing email templates
