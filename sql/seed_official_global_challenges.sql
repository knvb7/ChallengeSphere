\set ON_ERROR_STOP on

-- Flyway migration V33 owns the curated 25-template catalog and the
-- idempotent publication function. This operational script is safe to run
-- after migrations whenever the current week's three cohorts need to be
-- repaired or published immediately.

BEGIN;

SELECT challengesphere.ensure_rolling_global_cohorts(CURRENT_DATE)
    AS cohorts_created;

SELECT
    challenge.id,
    challenge.title,
    challenge.start_date,
    challenge.number_of_days,
    challenge.proof_mode,
    challenge.official_template_key,
    challenge.cohort_key
FROM challengesphere.challenges challenge
WHERE challenge.active = TRUE
  AND challenge.private = FALSE
  AND challenge.cohort_key IS NOT NULL
ORDER BY challenge.start_date DESC, challenge.title;

COMMIT;
