# Premium backend integration

This branch implements backend-authoritative Premium access with RevenueCat and Google Play. The mobile app can start a purchase, but it cannot grant Premium or write payment history. The backend verifies the RevenueCat entitlement and records signed webhook events.

## Data model

| Table | Purpose |
| --- | --- |
| `users.premium_user` | Fast compatibility flag used by challenge limits and ad eligibility. |
| `user_subscriptions` | Current verified entitlement, product, renewal, expiry, store, and environment. |
| `subscription_webhook_events` | RevenueCat webhook idempotency, payload hash, user/transaction association, and processing timestamps. |
| `payment_transactions` | Latest normalized state for each provider/store transaction. |
| `payment_history` | Immutable user-visible lifecycle ledger for purchases, renewals, cancellations, refunds, billing issues, product changes, and expiry. |
| `subscription_audit_log` | Premium entitlement grants, revocations, product changes, and status changes. |

The database does not store card numbers, bank details, CVVs, Google credentials, or client-submitted payment success claims.

## 1. Configure Google Play

1. Use Android package `com.challengesphere.app`.
2. Create subscription `challengesphere_premium`.
3. Add auto-renewing base plans `monthly` and `annual`, with no trial.
4. Set India prices to ₹149/month and ₹999/year.
5. Set United States prices to $2.99/month and $19.99/year.
6. Review the generated prices for every other enabled Play region.
7. Publish the products and a signed build to the internal testing track.
8. Add license testers and complete the payments profile, tax, Data safety, and subscription disclosures.

## 2. Connect RevenueCat

1. Create a RevenueCat Android app for `com.challengesphere.app`.
2. Connect the Google Play service account requested by RevenueCat.
3. Import both Google Play base plans.
4. Create entitlement `premium` and attach the monthly and annual products.
5. Create offering `default` with monthly and annual packages.
6. Create and publish the paywall used by the Android client.
7. Use RevenueCat App User IDs in the form `cs_user_<backend-user-id>`.

## 3. Configure the RevenueCat webhook

Create a RevenueCat webhook for:

```text
POST https://<api-host>/api/v1/subscriptions/webhooks/revenuecat
```

Configure a long random authorization header in RevenueCat. Store the exact full header value on the backend. Keep sandbox and production credentials separate.

The webhook handles these lifecycle events:

- `INITIAL_PURCHASE`
- `RENEWAL`
- `CANCELLATION`
- `UNCANCELLATION`
- `NON_RENEWING_PURCHASE`
- `SUBSCRIPTION_PAUSED`
- `EXPIRATION`
- `BILLING_ISSUE`
- `PRODUCT_CHANGE`
- `SUBSCRIPTION_EXTENDED`
- `REFUND_REVERSED`
- `INVOICE_ISSUANCE`

Dashboard test and paywall interaction events are idempotently acknowledged but are not added to payment history or used to change entitlements.

## 4. Configure backend secrets

```text
REVENUECAT_ENABLED=true
REVENUECAT_SECRET_API_KEY=sk_...
REVENUECAT_ENTITLEMENT_ID=premium
REVENUECAT_APP_USER_ID_PREFIX=cs_user_
REVENUECAT_WEBHOOK_AUTHORIZATION=Bearer <long-random-value>
```

On AWS production, keep the secret values in encrypted Parameter Store entries and
configure only their names in the runtime environment:

```text
REVENUECAT_SECRET_API_KEY_PARAMETER_NAME=/challengesphere/prod/revenuecat-secret-api-key
REVENUECAT_WEBHOOK_AUTHORIZATION_PARAMETER_NAME=/challengesphere/prod/revenuecat-webhook-authorization
```

When direct environment values are absent, the production profile reads and decrypts
those parameters through the EC2 instance role.

Optional custom HMAC gateway configuration:

```text
REVENUECAT_WEBHOOK_SIGNING_SECRET=<gateway-signing-secret>
REVENUECAT_WEBHOOK_TIMESTAMP_TOLERANCE_SECONDS=300
```

Never expose the RevenueCat secret API key or webhook secret to the mobile app.

## 5. Apply database migrations

Apply migrations in order:

1. `V46__add_premium_subscriptions.sql`
2. The repository's pending `V47` moderation migration
3. `V48__add_payment_ledger_and_subscription_audit.sql`

Production environments that apply SQL manually can run:

```text
sql/apply_premium_payment_migrations.sql
```

The manual script is idempotent and must run after the V46 tables exist.

## 6. Configure the Android build

The public Android SDK key belongs in the frontend build environment:

```text
EXPO_PUBLIC_REVENUECAT_ANDROID_API_KEY=goog_...
```

This public key identifies the RevenueCat app. It is different from the backend secret API key.

## 7. Backend API

Authenticated endpoints:

```text
GET  /api/v1/subscriptions/me
POST /api/v1/subscriptions/me/sync
GET  /api/v1/subscriptions/me/payments?page=0&pageSize=20
```

Public webhook endpoint protected by the configured webhook authorization:

```text
POST /api/v1/subscriptions/webhooks/revenuecat
```

Payment history is scoped to the authenticated user and cannot be queried by an arbitrary user ID.

## 8. Release verification

1. Buy monthly Premium with a Play license tester.
2. Confirm `payment_history` records `INITIAL_PURCHASE` and `users.premium_user` becomes `1`.
3. Confirm the user receives 15 public and 15 private active challenge slots and ads stop.
4. Deliver the same webhook twice and confirm only one history record exists.
5. Restore on a second device and confirm the same backend user retains Premium.
6. Cancel and confirm access remains active until expiry.
7. Deliver `EXPIRATION` and confirm Premium becomes free without deleting challenges.
8. Exercise billing issue, renewal, product change, refund, and sandbox/production separation.
9. Confirm deleted accounts retain financial history but never regain app entitlements from later webhooks.

## Operational notes

- RevenueCat webhook event IDs are treated as idempotency keys and payload hashes detect an ID reused with different content.
- Transaction state updates ignore older out-of-order webhook timestamps; the immutable history still keeps every received lifecycle event.
- Cancellation does not itself revoke access. The current RevenueCat entitlement remains authoritative, and expiry revokes access.
- Prices are stored in purchased currency when RevenueCat supplies them. RevenueCat's USD price is retained internally for reconciliation but is not returned by the user payment-history endpoint.
- Google Play financial reports remain the source for accounting reconciliation, taxes, fees, chargebacks, and settlement totals.

Official references:

- RevenueCat webhook event fields: https://www.revenuecat.com/docs/integrations/webhooks/event-types-and-fields
- RevenueCat webhook setup: https://www.revenuecat.com/docs/integrations/webhooks
- Google Play secure backend integration: https://developer.android.com/google/play/billing/backend
- Google Play billing security: https://developer.android.com/google/play/billing/security
