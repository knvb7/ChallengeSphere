# Active Challenge Participation Limit Design

## Goal

Limit each user to at most 10 simultaneous active challenge participations, while allowing operators to change the limit without rebuilding either application.

## Participation Definition

A participation consumes one slot only when all of the following are true:

- The `user_challenges` association is active.
- The association is not completed.
- The associated challenge is active.

Leaving or completing a challenge frees its slot. Creating or owning a challenge does not consume a slot unless the creator also has an active, incomplete participation association for that challenge.

Existing users who already exceed the configured limit are not removed from challenges. They cannot join or reactivate another participation until their count falls below the limit.

## Configuration

The backend is the single source of truth. It reads:

```properties
app.challenge.max-active-participations=${MAX_ACTIVE_CHALLENGE_PARTICIPATIONS:10}
```

The value must be a positive integer. The default is 10. Production can change the policy by setting `MAX_ACTIVE_CHALLENGE_PARTICIPATIONS` and restarting the backend.

The frontend does not maintain a separate environment value. It obtains the live limit from the backend participation-policy endpoint and uses 10 only as a temporary fallback while the policy request is unavailable.

## Backend Design

### Counting

`UserChallengeRepository` exposes a count query joining `user_challenges` to `challenges`. It counts active, incomplete associations whose challenge is active. Null legacy `completed` values are treated as incomplete.

### Concurrency

The existing transactional join flow acquires a pessimistic write lock on the joining user's `users` row before counting and saving participation. This serializes concurrent joins for the same user. After the first transaction commits the tenth participation, a concurrent transaction observes the new count and rejects an eleventh join.

The controller already replaces request user IDs with the authenticated user for direct joins. Invite redemption and notification acceptance both delegate to the same `joinChallenge` service, so the shared check covers every supported entry point.

### Rejections

Joining an already active participation remains idempotently unsuccessful under the existing behavior and does not consume another slot.

When a new or reactivated participation would exceed the limit, the backend throws `ChallengeSphereException` with HTTP 409 and this user-facing message:

```text
You can participate in at most {limit} active challenges at a time. Complete or leave a challenge before joining another.
```

No participation, leaderboard, notification, invite-usage, or accepted-challenge counters are changed when the join is rejected.

### Policy API

Add an authenticated endpoint:

```http
GET /api/v1/challenges/participation-policy
```

Response:

```json
{
  "maxActiveChallenges": 10
}
```

This endpoint exposes only a product policy value and no user-specific data.

## Frontend Design

The challenge service fetches the policy endpoint, and a React Query hook caches the response. Join surfaces use the returned value to prevent an obviously invalid request when the user's active count is already at the limit.

The existing hardcoded `MAX_ACTIVE_CHALLENGES = 15` is replaced by a default value of 10 plus the backend-provided live value. The global challenge screen shows the live number in its limit dialog.

The mutation layer remains backend-authoritative. If cached counts or policy data are stale, HTTP 409 is converted through the existing API error path and displayed to the user. This also covers joins initiated through invite and notification screens.

Successful joins continue to invalidate participant, user-challenge, and profile queries. Rejected joins do not invalidate caches.

## Testing

Backend focused tests cover:

- Nine active participations permit the tenth.
- Ten active participations reject the eleventh with HTTP 409.
- Completed, left, and inactive-challenge associations do not consume slots.
- A reactivated association is subject to the same limit.
- The policy endpoint returns the configured value.
- User-row locking occurs before the authoritative count, protecting concurrent joins.

Frontend focused tests cover:

- Policy responses are parsed and cached.
- The global join flow uses the live configured limit.
- The frontend fallback is 10 when policy data is unavailable.
- A backend 409 message is preserved for display.

## Scope

This change does not automatically evict existing over-limit users, alter challenge ownership, add subscription-tier limits, or add an administrative settings UI.
