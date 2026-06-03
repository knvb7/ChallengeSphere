package com.ironside.challengesphere.api.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Email;
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
    private Long userId;

    @Email(message = "Email should be valid")
    @Size(max = 255, message = "Email should not exceed 255 characters")
    private String emailId;

    @Size(max = 20, message = "Phone number should not exceed 20 characters")
    private String phone;

    @Size(max = 50, message = "Username must be between 3 and 50 characters")
    private String username;

    private String hashedPassword;

    @JsonAlias({"genuinenessScore"})
    private Integer genuinityScore;

    // Backward-compatible field name; prefer genuinityScore going forward.
    private Integer genuinenessScore;

    private Boolean active;

    private Integer challengesCreated;

    private Integer challengesAccepted;

    private String profilePicUrl;

    private Integer premiumUser;

    private Integer notifications;

    @Size(max = 500, message = "Bio should not exceed 500 characters")
    private String bio;

    private LocalDate dateOfBirth;

    @Min(value = 0, message = "Age should not be negative")
    @Max(value = 130, message = "Age should not exceed 130")
    private Integer age;

    private List<String> interests;
}
