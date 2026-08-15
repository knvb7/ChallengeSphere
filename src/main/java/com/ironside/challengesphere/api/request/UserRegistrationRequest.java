package com.ironside.challengesphere.api.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ironside.challengesphere.util.AccountAgePolicy;
import com.ironside.challengesphere.util.UsernamePolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UserRegistrationRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String emailId;

    @Pattern(regexp = "^[0-9]{10,20}$", message = "Phone number must be 10-20 digits")
    private String phone;

    @NotBlank(message = "Username is required")
    @Size(
            min = UsernamePolicy.MIN_LENGTH,
            max = UsernamePolicy.MAX_LENGTH,
            message = UsernamePolicy.LENGTH_MESSAGE
    )
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers, and underscores")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
    private String password;

    private String bio;

    private LocalDate dateOfBirth;

    @JsonIgnore
    @AssertTrue(message = "Date of birth must represent an age between 13 and 120")
    public boolean isDateOfBirthEligible() {
        return dateOfBirth == null || AccountAgePolicy.isEligibleDateOfBirth(dateOfBirth);
    }

    private String otpCode;
}
