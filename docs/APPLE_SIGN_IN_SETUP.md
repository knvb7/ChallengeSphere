# Sign in with Apple production setup

The backend implementation verifies Apple identity-token signatures, issuer, audience,
issued-at time, subject, and nonce. It exchanges each single-use authorization code,
stores the Apple refresh token with AES-256-GCM application encryption, revokes it when
a user deletes their ChallengeSphere account, and processes Apple account-change
notifications idempotently.

## Apple Developer account

1. Enable Sign in with Apple on App ID `com.ironside.challengesphere`.
2. Create a Sign in with Apple private key and securely download its `.p8` file.
3. Record the Apple Team ID and key ID.
4. Register this TLS endpoint as the App ID’s server-to-server notification URL:

```text
https://<api-host>/api/v1/auth/apple/notifications
```

5. Regenerate the iOS provisioning profile after enabling the capability.

Apple setup references:

- https://developer.apple.com/documentation/xcode/configuring-sign-in-with-apple
- https://developer.apple.com/help/account/capabilities/enabling-server-to-server-notifications/

## Backend secrets

Configure these values in the production secret manager. Do not commit the `.p8` key or
the encryption key.

```text
APPLE_SIGNIN_ENABLED=true
APPLE_SIGNIN_CLIENT_IDS=com.ironside.challengesphere
APPLE_TEAM_ID=<10-character team ID>
APPLE_KEY_ID=<Sign in with Apple key ID>
APPLE_PRIVATE_KEY_BASE64=<base64-encoded .p8 contents>
APPLE_TOKEN_ENCRYPTION_KEY_BASE64=<base64-encoded 32-byte random key>
```

Generate the token-encryption key once and keep it stable. Rotating it without a migration
would make existing encrypted Apple refresh tokens unreadable.

Apply database migrations `V48` through `V51` before enabling Apple sign-in.

## iOS client

The iOS target must contain:

- the Sign in with Apple capability and `com.apple.developer.applesignin` entitlement;
- the Associated Domains capability for `applinks:challengesphere.com`;
- the native Apple authentication button;
- no Google Sign-In SDK in the iOS binary.

## Operational checks

- New Apple account with real email.
- New Apple account with Hide My Email.
- Returning sign-in, including a second device.
- Safe linking when Apple’s verified email exactly matches an existing account.
- Revoked credential logs the app out.
- Account deletion queues Apple token revocation without storing a plaintext token.
- Account deletion stores no raw provider email, username, or display name in its audit row. Keyed email/username fingerprints expire after 90 days; set a stable `ACCOUNT_DELETION_IDENTITY_HASH_KEY` of at least 32 characters in production.
- Duplicate Apple server notifications return HTTP 200 without repeating deletion work.
- `email-enabled`, `email-disabled`, `consent-revoked`, and `account-deleted` notifications.
