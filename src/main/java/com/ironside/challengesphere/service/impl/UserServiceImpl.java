package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.UserUpdateRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.UserProfileResponse;
import com.ironside.challengesphere.model.Following;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.FollowingRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final Logger logger = LogManager.getLogger(UserServiceImpl.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FollowingRepository followingRepository;

    @Override
    public UserProfileResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));

        return mapToUserProfileResponse(user);
    }

    private UserProfileResponse mapToUserProfileResponse(User user) {
        UserProfileResponse response = new UserProfileResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmailId());
        response.setFullName(user.getFullName());
        response.setLocation(user.getLocation());
        response.setBio(user.getBio());
        response.setActive(user.getActive()); // Set active as int (0/1)
        response.setRole(user.getRole());
        response.setProfilePicUrl(user.getProfilePicUrl());

        // These would normally be calculated from database queries
        Long followers = followingRepository.findFollowersCount(user.getUserId());
        Long following = followingRepository.findFollowedByCount(user.getUserId());
        response.setFollowersCount(followers);
        response.setFollowingCount(following);

        // Convert OffsetDateTime to Instant for the response
        if (user.getCreatedAt() != null) {
            response.setCreatedAt(user.getCreatedAt().toInstant());
        }
        response.setUpdatedAt(java.time.Instant.now()); // This would be set from the user entity in a real app

        return response;
    }


    @Override
    @Transactional
    public UserProfileResponse updateUserProfile(Long userId, UserUpdateRequest updateRequest) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));

        // Update fields if they are provided in the request
        if (updateRequest.getFullName() != null) {
            user.setFullName(updateRequest.getFullName());
        }
        if (updateRequest.getLocation() != null) {
            user.setLocation(updateRequest.getLocation());
        }
        if (updateRequest.getBio() != null) {
            user.setBio(updateRequest.getBio());
        }
        if (updateRequest.getLanguagePreference() != null) {
            user.setLanguagePreference(updateRequest.getLanguagePreference());
        }
        if (updateRequest.getPreferredTone() != null) {
            user.setPreferredTone(updateRequest.getPreferredTone());
        }

        // Save updated user
        User savedUser = userRepository.save(user);

        return mapToUserProfileResponse(savedUser);
    }

    @Override
    public List<UserProfileResponse> getLeaderboard(Long challengeId) {
        // This would normally include complex queries to determine user rankings
        // For now, just return all users as a basic implementation
        List<User> users = userRepository.findAllBy();
        List<UserProfileResponse> leaderboard = new ArrayList<>();

        for (User user : users) {
            UserProfileResponse profile = mapToUserProfileResponse(user);
            leaderboard.add(profile);
        }

        return leaderboard;
    }

    @Override
    @Transactional
    public void toggleFollow(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new IllegalArgumentException("You cannot follow/unfollow yourself.");
        }

        // Check if both users exist
        User follower = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));
        User followed = userRepository.findById(targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + targetUserId));

        boolean isFollowing = followingRepository.existsByFollowerUserIdAndFollowedUserId(userId, targetUserId);

        if (isFollowing) {
            // Unfollow
            followingRepository.deleteByFollowerAndFollowed(follower, followed);
        } else {
            // Follow
            Following following = new Following();
            following.setFollower(follower);
            following.setFollowed(followed);
            following.setCreatedAt(Timestamp.from(Instant.now()));
            followingRepository.save(following);
        }
    }

    @Override
    @Transactional
    public UserProfileResponse registerNewUser(UserRegistrationRequest userRegistrationRequest) {
        if (userRepository.existsByUsername(userRegistrationRequest.getUsername())) {
            throw new IllegalArgumentException("Error: Username is already taken!");
        }
        if (userRepository.existsByEmailId(userRegistrationRequest.getEmailId())) {
            throw new IllegalArgumentException("Error: Email is already in use!");
        }
        if (userRegistrationRequest.getPhoneNumber() != null && userRepository.existsByPhoneNumber(userRegistrationRequest.getPhoneNumber())) {
            throw new IllegalArgumentException("Error: Phone number is already in use!");
        }
        User newUser = new User();
        newUser.setUsername(userRegistrationRequest.getUsername());
        newUser.setEmailId(userRegistrationRequest.getEmailId());
        newUser.setPasswordHash(userRegistrationRequest.getPassword());
        newUser.setFullName(userRegistrationRequest.getFullName());
        newUser.setLocation(userRegistrationRequest.getLocation());
        newUser.setBio(userRegistrationRequest.getBio());
        newUser.setPhoneNumber(userRegistrationRequest.getPhoneNumber());
        newUser.setLanguagePreference("en");
        newUser.setPreferredTone("motivational");
        newUser.setActive(1);
        User savedUser = userRepository.save(newUser);
        return mapToUserProfileResponse(savedUser);
    }

    @Override
    @Transactional
    public UserProfileResponse updateUserBio(Long userId, String bio) {
        User user = userRepository.findById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
        user.setBio(bio);
        User savedUser = userRepository.save(user);
        return mapToUserProfileResponse(savedUser);
    }

    @Override
    @Transactional
    public UserProfileResponse updateUserLocation(Long userId, String location) {
        User user = userRepository.findById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
        user.setLocation(location);
        User savedUser = userRepository.save(user);
        return mapToUserProfileResponse(savedUser);
    }

    @Override
    @Transactional
    public UserProfileResponse updateProfilePicture(Long userId, String profilePicUrl) {
        User user = userRepository.findById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
        user.setProfilePicUrl(profilePicUrl);
        User savedUser = userRepository.save(user);
        return mapToUserProfileResponse(savedUser);
    }

    @Override
    @Transactional
    public UserProfileResponse updateUserPreferences(Long userId, String language, String tone) {
        User user = userRepository.findById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
        if (language != null) user.setLanguagePreference(language);
        if (tone != null) user.setPreferredTone(tone);
        User savedUser = userRepository.save(user);
        return mapToUserProfileResponse(savedUser);
    }

    @Override
    @Transactional
    public int toggleActivation(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
        int newStatus = user.getActive() == 1 ? 0 : 1;
        user.setActive(newStatus);
        userRepository.save(user);
        return newStatus;
    }

}