package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.util.AccountAgePolicy;
import com.ironside.challengesphere.util.UserGender;
import com.ironside.challengesphere.util.UserRole;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelfUserResponse extends PublicUserResponse {
    private String emailId;
    private String phone;
    private Integer notifications;
    private LocalDate dateOfBirth;
    private Integer age;
    private UserGender gender;
    private String country;
    private Boolean profileComplete;
    private UserRole role;

    public static SelfUserResponse from(User user) {
        if (user == null) {
            return null;
        }
        SelfUserResponse response = new SelfUserResponse();
        populate(response, user);
        LocalDate today = AccountAgePolicy.currentDate();
        LocalDate dateOfBirth = user.getDateOfBirth();
        Integer derivedAge = AccountAgePolicy.isEligibleDateOfBirth(dateOfBirth, today)
                ? AccountAgePolicy.calculateAge(dateOfBirth, today)
                : null;
        response.setEmailId(user.getEmailId());
        response.setPhone(user.getPhone());
        response.setNotifications(user.getNotifications());
        response.setDateOfBirth(dateOfBirth);
        response.setAge(derivedAge);
        response.setGender(user.getGender());
        response.setCountry(user.getCountry());
        response.setProfileComplete(hasCompletedRequiredProfile(user, derivedAge));
        response.setRole(user.getRole());
        return response;
    }

    private static boolean hasCompletedRequiredProfile(User user, Integer derivedAge) {
        List<String> interests = user.getInterests();
        return hasText(user.getName())
                && hasText(user.getUsername())
                && hasText(user.getProfilePicUrl())
                && hasText(user.getBio())
                && derivedAge != null
                && user.getGender() != null
                && hasText(user.getCountry())
                && interests != null
                && interests.stream().anyMatch(SelfUserResponse::hasText);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
