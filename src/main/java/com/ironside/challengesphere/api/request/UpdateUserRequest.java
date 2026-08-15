package com.ironside.challengesphere.api.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ironside.challengesphere.util.AccountAgePolicy;
import com.ironside.challengesphere.util.UserGender;
import com.ironside.challengesphere.util.UsernamePolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
public class UpdateUserRequest {
    @Size(
            min = UsernamePolicy.MIN_LENGTH,
            max = UsernamePolicy.MAX_LENGTH,
            message = UsernamePolicy.LENGTH_MESSAGE
    )
    private String username;

    @Size(max = 120, message = "Name should not exceed 120 characters")
    private String name;

    @Size(max = 120, message = "Full name should not exceed 120 characters")
    private String fullName;

    @Size(max = 120, message = "Display name should not exceed 120 characters")
    private String displayName;

    private String profilePicUrl;

    private Integer notifications;

    @Size(max = 500, message = "Bio should not exceed 500 characters")
    private String bio;

    private LocalDate dateOfBirth;

    @Min(value = AccountAgePolicy.MINIMUM_ACCOUNT_AGE, message = "You must be at least 13 years old")
    @Max(value = AccountAgePolicy.MAXIMUM_ACCOUNT_AGE, message = "Age should not exceed 120")
    private Integer age;

    @JsonIgnore
    @AssertTrue(message = "Date of birth must represent an age between 13 and 120")
    public boolean isDateOfBirthEligible() {
        return dateOfBirth == null || AccountAgePolicy.isEligibleDateOfBirth(dateOfBirth);
    }

    @JsonIgnore
    @AssertTrue(message = "Age must match date of birth")
    public boolean isAgeConsistentWithDateOfBirth() {
        return dateOfBirth == null
                || age == null
                || age == AccountAgePolicy.calculateAge(dateOfBirth);
    }

    private UserGender gender;

    @Size(min = 2, max = 100, message = "Country must be between 2 and 100 characters")
    private String country;

    private List<String> interests;
}
