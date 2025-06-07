package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.UserUpdateRequest;
import com.ironside.challengesphere.api.response.UserProfileResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface UserService {
    UserProfileResponse getUserProfile(Long userId);

    UserProfileResponse updateUserProfile(Long userId, UserUpdateRequest updateRequest);

    List<UserProfileResponse> getLeaderboard();

    void toggleFollow(Long userId, Long targetUserId);

    UserProfileResponse registerNewUser(com.ironside.challengesphere.api.request.UserRegistrationRequest userRegistrationRequest);

    UserProfileResponse updateUserBio(Long userId, String bio);

    UserProfileResponse updateUserLocation(Long userId, String location);

    UserProfileResponse updateProfilePicture(Long userId, String profilePicUrl);

    UserProfileResponse updateUserPreferences(Long userId, String language, String tone);

    int toggleActivation(Long userId);
}
