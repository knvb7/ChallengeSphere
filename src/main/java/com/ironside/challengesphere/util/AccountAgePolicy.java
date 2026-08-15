package com.ironside.challengesphere.util;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;

public final class AccountAgePolicy {
    public static final int MINIMUM_ACCOUNT_AGE = 13;
    public static final int MAXIMUM_ACCOUNT_AGE = 120;

    private AccountAgePolicy() {
    }

    public static LocalDate currentDate() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    public static int calculateAge(LocalDate dateOfBirth) {
        return calculateAge(dateOfBirth, currentDate());
    }

    public static int calculateAge(LocalDate dateOfBirth, LocalDate today) {
        return Period.between(dateOfBirth, today).getYears();
    }

    public static boolean isEligibleDateOfBirth(LocalDate dateOfBirth) {
        return isEligibleDateOfBirth(dateOfBirth, currentDate());
    }

    public static boolean isEligibleDateOfBirth(LocalDate dateOfBirth, LocalDate today) {
        if (dateOfBirth == null) {
            return false;
        }

        int age = calculateAge(dateOfBirth, today);
        return !dateOfBirth.isAfter(today)
                && age >= MINIMUM_ACCOUNT_AGE
                && age <= MAXIMUM_ACCOUNT_AGE;
    }
}
