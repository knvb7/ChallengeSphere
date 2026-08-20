# ChallengeSphere moderation operations

Status: backend triage signals and queues are ready; production ownership and alert routing must be completed before the iOS release.

## Required owner and contact

Assign these before deployment:

- Primary moderation owner: `[name and role]`
- Backup moderator: `[name and role]`
- Monitored urgent destination: `[pager, incident channel, or on-call destination]`
- Safety email: `safety@challengesphere.com` — confirm it is monitored and routes to both owners
- After-hours escalation: `[name, service, or rotation]`

Do not publish an App Review response-time claim that the assigned team cannot meet.

## Queue priority

The admin content-report queue returns reports in this order:

1. `CRITICAL`: `CHILD_SAFETY`
2. `URGENT`: `SELF_HARM`, `VIOLENCE`
3. `STANDARD`: harassment, hate, nudity, spam, and other reports

The API is `GET /api/v1/admin/content-reports` and requires an authenticated admin account. Account reports are available at `GET /api/v1/admin/user-reports`.

Recommended internal targets:

- Critical: acknowledge within 15 minutes and begin review within 1 hour.
- Urgent: begin review within 4 hours.
- Standard: begin review within 24 hours.

These are internal operating targets, not a public guarantee.

## Production alert routing

Critical and urgent content or account reports emit a structured error log containing this stable marker:

```text
URGENT_MODERATION_REPORT
```

Configure the production logging platform to:

1. Match that exact marker.
2. Notify the monitored urgent destination.
3. Include timestamp, environment, report ID, reason, target type, and target ID.
4. Exclude report details, evidence content, email addresses, names, and access tokens from the notification.
5. Escalate when the alert is not acknowledged within 15 minutes.
6. Alarm separately when the application stops delivering logs.

The release gate is not complete until a real production report has generated and acknowledged a test alert at the chosen destination. Do not create an objectionable-content report for this check; use an admin-approved synthetic database record or a temporary non-public staging environment.

## Review procedure

1. Open the admin queue and preserve the stored evidence snapshot.
2. Check the reported target and related account history without contacting the reporter or reported user from a personal account.
3. Select the narrowest safe resolution: dismiss, remove content, or ban the account.
4. For apparent child sexual abuse material or immediate danger, stop routine handling and follow the applicable legal/escalation process. Do not download or redistribute illegal material.
5. Record the reviewer and resolution in the queue.
6. Confirm removed content is no longer visible and revoked accounts cannot use existing sessions.

## Daily release-period checks

- Review open content reports, account reports, and image moderation flags at least daily.
- Check oldest-open age by priority and investigate missed targets.
- Confirm the safety and support mailboxes have no unassigned messages.
- Confirm alert delivery and application-log ingestion are healthy.
- Keep at least two active admin accounts with separate credentials and multi-factor authentication on the identity provider where available.

## Still required

- Name the primary and backup owners.
- Configure and prove the monitored production alert destination.
- Decide whether ambiguous text is held for review or published with a queued moderation state, then implement that state consistently for profiles, challenges, submissions, and comments.
