package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Challenge;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.List;

/** Read model used by the local-evening motivation reminder scheduler. */
public interface MotivationReminderRepository extends Repository<Challenge, Long> {

    @Query(value = """
            SELECT DISTINCT uc.user_id AS "userId",
                   COALESCE(latest_token.timezone, 'UTC') AS "timezone"
            FROM challengesphere.user_challenges uc
            JOIN challengesphere.users users ON users.id = uc.user_id
            JOIN challengesphere.challenges c ON c.id = uc.challenge_id
            LEFT JOIN challengesphere.user_preferences preferences
              ON preferences.user_id = uc.user_id
            LEFT JOIN LATERAL (
                SELECT token.timezone
                FROM challengesphere.push_device_tokens token
                WHERE token.user_id = uc.user_id
                ORDER BY token.last_seen_at DESC, token.id DESC
                LIMIT 1
            ) latest_token ON true
            WHERE uc.active = true
              AND COALESCE(uc.banned, false) = false
              AND COALESCE(uc.completed, false) = false
              AND COALESCE(users.active, true) = true
              AND CASE
                    WHEN preferences.user_id IS NULL THEN
                      ((COALESCE(users.notifications, 1) & 1) = 1)
                    ELSE COALESCE(preferences.notify_on_motivation, true)
                  END = true
              AND c.active = true
              AND UPPER(COALESCE(c.challenge_mode, 'DAILY')) = 'DAILY'
              AND (
                  COALESCE(c.private, false) = false
                  OR EXISTS (
                      SELECT 1
                      FROM challengesphere.challenge_crew_lifecycle lifecycle
                      WHERE lifecycle.challenge_id = c.id
                        AND lifecycle.started_at IS NOT NULL
                  )
              )
            """, nativeQuery = true)
    List<EveningUserProjection> findMotivationUsers();

    @Query(value = """
            SELECT c.id AS "challengeId",
                   c.title AS "challengeTitle",
                   c.start_date AS "startDate",
                   c.number_of_days AS "totalDays",
                   c.challenge_mode AS "challengeMode",
                   c.active AS "challengeActive",
                   COALESCE(uc.completed, false) AS "participantCompleted",
                   (:localDate - c.start_date + 1) AS "dayNumber",
                   COUNT(DISTINCT s.day_number)::int AS "completedDays",
                   COALESCE(c.private, false) AS "privateChallenge",
                   CASE
                     WHEN COALESCE(c.private, false) = true THEN NOT EXISTS (
                       SELECT 1
                       FROM challengesphere.challenge_crew_lifecycle lifecycle_state
                       WHERE lifecycle_state.challenge_id = c.id
                         AND lifecycle_state.started_at IS NOT NULL
                     )
                     ELSE false
                   END AS "privateLobby",
                   CASE
                     WHEN COALESCE(c.private, false) = true AND self_entry.id IS NOT NULL THEN (
                       SELECT COUNT(higher.id) + 1
                       FROM challengesphere.leaderboard_entries higher
                       WHERE higher.challenge_id = c.id
                         AND higher.status = 'ACTIVE'
                         AND higher.score > self_entry.score
                     )
                     ELSE NULL
                   END AS "rank",
                   CASE
                     WHEN COALESCE(c.private, false) = true THEN (
                       SELECT COUNT(ranked.id)
                       FROM challengesphere.leaderboard_entries ranked
                       WHERE ranked.challenge_id = c.id
                         AND ranked.status = 'ACTIVE'
                     )
                     ELSE NULL
                   END AS "totalParticipants"
            FROM challengesphere.user_challenges uc
            JOIN challengesphere.challenges c ON c.id = uc.challenge_id
            LEFT JOIN challengesphere.submissions s
              ON s.user_id = uc.user_id
             AND s.challenge_id = c.id
             AND s.active = true
             AND s.day_number BETWEEN 1 AND c.number_of_days
            LEFT JOIN challengesphere.leaderboard_entries self_entry
              ON self_entry.challenge_id = c.id
             AND self_entry.user_id = uc.user_id
             AND self_entry.status = 'ACTIVE'
            WHERE uc.user_id = :userId
              AND uc.active = true
              AND COALESCE(uc.banned, false) = false
              AND COALESCE(uc.completed, false) = false
              AND c.active = true
              AND UPPER(COALESCE(c.challenge_mode, 'DAILY')) = 'DAILY'
              AND :localDate BETWEEN c.start_date AND (c.start_date + (c.number_of_days - 1))
              AND (
                  COALESCE(c.private, false) = false
                  OR EXISTS (
                      SELECT 1
                      FROM challengesphere.challenge_crew_lifecycle lifecycle
                      WHERE lifecycle.challenge_id = c.id
                        AND lifecycle.started_at IS NOT NULL
                  )
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM challengesphere.submissions today_submission
                  WHERE today_submission.user_id = uc.user_id
                    AND today_submission.challenge_id = c.id
                    AND today_submission.active = true
                    AND today_submission.day_number = (:localDate - c.start_date + 1)
              )
            GROUP BY c.id, c.title, c.start_date, c.number_of_days, c.challenge_mode,
                     c.active, c.private, uc.completed, self_entry.id, self_entry.score
            """, nativeQuery = true)
    List<PendingMotivationProjection> findPendingForUser(
            @Param("userId") Long userId,
            @Param("localDate") LocalDate localDate);

    @Query(value = """
            SELECT uc.user_id AS "userId",
                   latest_token.timezone AS "timezone",
                   ((CAST(:now AS timestamptz) AT TIME ZONE latest_token.timezone)::date) AS "localDate",
                   c.id AS "challengeId",
                   c.title AS "challengeTitle",
                   c.start_date AS "startDate",
                   c.number_of_days AS "totalDays",
                   c.challenge_mode AS "challengeMode",
                   c.active AS "challengeActive",
                   COALESCE(uc.completed, false) AS "participantCompleted",
                   (((CAST(:now AS timestamptz) AT TIME ZONE latest_token.timezone)::date)
                     - c.start_date + 1) AS "dayNumber",
                   COUNT(DISTINCT s.day_number)::int AS "completedDays",
                   COALESCE(c.private, false) AS "privateChallenge",
                   CASE
                     WHEN COALESCE(c.private, false) = true THEN NOT EXISTS (
                       SELECT 1
                       FROM challengesphere.challenge_crew_lifecycle lifecycle_state
                       WHERE lifecycle_state.challenge_id = c.id
                         AND lifecycle_state.started_at IS NOT NULL
                     )
                     ELSE false
                   END AS "privateLobby",
                   CASE
                     WHEN COALESCE(c.private, false) = true AND self_entry.id IS NOT NULL THEN (
                       SELECT COUNT(higher.id) + 1
                       FROM challengesphere.leaderboard_entries higher
                       WHERE higher.challenge_id = c.id
                         AND higher.status = 'ACTIVE'
                         AND higher.score > self_entry.score
                     )
                     ELSE NULL
                   END AS "rank",
                   CASE
                     WHEN COALESCE(c.private, false) = true THEN (
                       SELECT COUNT(ranked.id)
                       FROM challengesphere.leaderboard_entries ranked
                       WHERE ranked.challenge_id = c.id
                         AND ranked.status = 'ACTIVE'
                     )
                     ELSE NULL
                   END AS "totalParticipants"
            FROM challengesphere.user_challenges uc
            JOIN challengesphere.users users ON users.id = uc.user_id
            JOIN challengesphere.challenges c ON c.id = uc.challenge_id
            LEFT JOIN challengesphere.user_preferences preferences
              ON preferences.user_id = uc.user_id
            JOIN LATERAL (
                SELECT token.timezone
                FROM challengesphere.push_device_tokens token
                WHERE token.user_id = uc.user_id
                  AND token.enabled = true
                ORDER BY token.last_seen_at DESC, token.id DESC
                LIMIT 1
            ) latest_token ON true
            LEFT JOIN challengesphere.submissions s
              ON s.user_id = uc.user_id
             AND s.challenge_id = c.id
             AND s.active = true
             AND s.day_number BETWEEN 1 AND c.number_of_days
            LEFT JOIN challengesphere.leaderboard_entries self_entry
              ON self_entry.challenge_id = c.id
             AND self_entry.user_id = uc.user_id
             AND self_entry.status = 'ACTIVE'
            WHERE uc.active = true
              AND COALESCE(uc.banned, false) = false
              AND COALESCE(uc.completed, false) = false
              AND COALESCE(users.active, true) = true
              AND (COALESCE(users.notifications, 1) & 1) = 1
              AND COALESCE(preferences.push_notifications_enabled, true) = true
              AND COALESCE(preferences.notify_on_motivation, true) = true
              AND c.active = true
              AND UPPER(COALESCE(c.challenge_mode, 'DAILY')) = 'DAILY'
              AND ((CAST(:now AS timestamptz) AT TIME ZONE latest_token.timezone)::date)
                    BETWEEN c.start_date AND (c.start_date + (c.number_of_days - 1))
              AND (
                  COALESCE(c.private, false) = false
                  OR EXISTS (
                      SELECT 1
                      FROM challengesphere.challenge_crew_lifecycle lifecycle
                      WHERE lifecycle.challenge_id = c.id
                        AND lifecycle.started_at IS NOT NULL
                  )
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM challengesphere.submissions today_submission
                  WHERE today_submission.user_id = uc.user_id
                    AND today_submission.challenge_id = c.id
                    AND today_submission.active = true
                    AND today_submission.day_number = (
                        ((CAST(:now AS timestamptz) AT TIME ZONE latest_token.timezone)::date)
                        - c.start_date + 1
                    )
              )
            GROUP BY uc.user_id, latest_token.timezone, c.id, c.title, c.start_date,
                     c.number_of_days, c.challenge_mode, c.active, c.private,
                     uc.completed, self_entry.id, self_entry.score
            """, nativeQuery = true)
    List<ScheduledMotivationProjection> findAllPending(@Param("now") Timestamp now);

    interface PendingMotivationProjection {
        Long getChallengeId();

        String getChallengeTitle();

        LocalDate getStartDate();

        Integer getTotalDays();

        String getChallengeMode();

        Boolean getChallengeActive();

        Boolean getParticipantCompleted();

        Integer getDayNumber();

        Integer getCompletedDays();

        Boolean getPrivateChallenge();

        Boolean getPrivateLobby();

        Long getRank();

        Long getTotalParticipants();
    }

    interface ScheduledMotivationProjection extends PendingMotivationProjection {
        Long getUserId();

        String getTimezone();

        LocalDate getLocalDate();
    }

    interface EveningUserProjection {
        Long getUserId();

        String getTimezone();
    }
}
