package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ironside.challengesphere.model.User;
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
        response.setEmailId(user.getEmailId());
        response.setPhone(user.getPhone());
        response.setNotifications(user.getNotifications());
        response.setDateOfBirth(user.getDateOfBirth());
        response.setAge(user.getAge());
        response.setGender(user.getGender());
        response.setCountry(user.getCountry());
        response.setProfileComplete(hasCompletedRequiredProfile(user));
        response.setRole(user.getRole());
        return response;
    }

    private static boolean hasCompletedRequiredProfile(User user) {
        List<String> interests = user.getInterests();
        return hasText(user.getName())
                && hasText(user.getUsername())
                && hasText(user.getProfilePicUrl())
                && hasText(user.getBio())
                && user.getDateOfBirth() != null
                && user.getAge() != null
                && user.getGender() != null
                && hasText(user.getCountry())
                && interests != null
                && interests.stream().anyMatch(SelfUserResponse::hasText);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
