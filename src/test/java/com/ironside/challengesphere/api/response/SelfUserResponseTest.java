package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.util.UserGender;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUserResponseTest {

    @Test
    void profileIsIncompleteUntilEveryRequiredFieldExists() {
        User user = completeUser();
        user.setCountry(null);

        SelfUserResponse response = SelfUserResponse.from(user);

        assertFalse(response.getProfileComplete());
    }

    @Test
    void profileIsCompleteWhenEveryRequiredFieldExists() {
        SelfUserResponse response = SelfUserResponse.from(completeUser());

        assertTrue(response.getProfileComplete());
    }

    private static User completeUser() {
        User user = new User();
        user.setUsername("challenger");
        user.setName("Challenge User");
        user.setProfilePicUrl("profiles/1/avatar.jpg");
        user.setBio("Building every day");
        user.setDateOfBirth(LocalDate.of(2000, 1, 1));
        user.setAge(26);
        user.setGender(UserGender.PREFER_NOT_TO_SAY);
        user.setCountry("India");
        user.setInterests(List.of("fitness"));
        return user;
    }
}
