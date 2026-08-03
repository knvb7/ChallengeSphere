package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.Challenge;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Canonical, side-effect-free policy for goal momentum on daily challenges.
 *
 * <p>An empty result means that momentum does not apply to the supplied challenge. A result with
 * {@link State#NONE} means the challenge is eligible, but has not entered its final motivational
 * window yet.</p>
 */
@Component
public class ChallengeMomentumPolicy {

    public enum State {
        NONE,
        FINAL_STRETCH,
        FINISH_STRONG
    }

    public record Input(
            String challengeMode,
            boolean challengeActive,
            boolean participantCompleted,
            boolean privateChallenge,
            boolean privateLobby,
            LocalDate startDate,
            int totalDays,
            int completedDays,
            boolean submittedToday,
            LocalDate currentDate
    ) {
    }

    public record Result(
            State state,
            int totalDays,
            int completedDays,
            int remainingRequiredCheckIns,
            int remainingAvailableCheckIns,
            boolean submittedToday
    ) {
    }

    public Optional<Result> evaluate(Input input) {
        if (!isEligible(input)) {
            return Optional.empty();
        }

        int completedDays = Math.min(input.completedDays(), input.totalDays());
        int currentDay = Math.toIntExact(ChronoUnit.DAYS.between(input.startDate(), input.currentDate())) + 1;
        int remainingRequired = input.totalDays() - completedDays;
        int remainingAvailable = Math.max(0, input.totalDays() - currentDay)
                + (input.submittedToday() ? 0 : 1);
        int finalWindow = finalWindowDays(input.totalDays());

        State state = State.NONE;
        if (remainingRequired > 0 && remainingAvailable > 0) {
            boolean completionReachable = remainingRequired <= remainingAvailable;
            if (completionReachable && remainingRequired <= finalWindow) {
                state = State.FINAL_STRETCH;
            } else if (!completionReachable && remainingAvailable <= finalWindow) {
                state = State.FINISH_STRONG;
            }
        }

        return Optional.of(new Result(
                state,
                input.totalDays(),
                completedDays,
                remainingRequired,
                remainingAvailable,
                input.submittedToday()
        ));
    }

    public int finalWindowDays(int totalDays) {
        if (totalDays <= 0) {
            throw new IllegalArgumentException("totalDays must be positive");
        }
        int thirtyPercent = (int) Math.ceil(totalDays * 0.30d);
        return totalDays <= 7 ? thirtyPercent : Math.max(7, thirtyPercent);
    }

    private boolean isEligible(Input input) {
        if (input == null
                || input.currentDate() == null
                || input.startDate() == null
                || input.totalDays() <= 0
                || input.completedDays() < 0
                || !input.challengeActive()
                || input.participantCompleted()
                || input.completedDays() >= input.totalDays()
                || !Challenge.MODE_DAILY.equalsIgnoreCase(input.challengeMode())
                || (input.privateChallenge() && input.privateLobby())) {
            return false;
        }

        LocalDate challengeEndExclusive = input.startDate().plusDays(input.totalDays());
        return !input.currentDate().isBefore(input.startDate())
                && input.currentDate().isBefore(challengeEndExclusive);
    }
}
