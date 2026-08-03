package com.ironside.challengesphere.api.request;

import com.ironside.challengesphere.util.UserGender;
import com.ironside.challengesphere.util.UsernamePolicy;
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

    @Min(value = 0, message = "Age should not be negative")
    @Max(value = 130, message = "Age should not exceed 130")
    private Integer age;

    private UserGender gender;

    @Size(min = 2, max = 100, message = "Country must be between 2 and 100 characters")
    private String country;

    private List<String> interests;
}
