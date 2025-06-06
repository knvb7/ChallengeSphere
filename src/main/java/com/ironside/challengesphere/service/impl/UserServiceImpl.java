package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.UserUpdateRequest;
import com.ironside.challengesphere.api.response.UserProfileResponse;
import com.ironside.challengesphere.model.Following;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.FollowingRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

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
        response.setActive(true); // Add logic to determine if user is active
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
    public List<UserProfileResponse> getLeaderboard() {
        // This would normally include complex queries to determine user rankings
        // For now, just return all users as a basic implementation
        List<User> users = userRepository.findAll();
        List<UserProfileResponse> leaderboard = new ArrayList<>();

        for (User user : users) {
            UserProfileResponse profile = mapToUserProfileResponse(user);
            leaderboard.add(profile);
        }

        return leaderboard;
    }

    @Override
    @Transactional
    public void followUser(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new IllegalArgumentException("You cannot follow yourself.");
        }
        // Check if both users exist
        User follower = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));
        User followed = userRepository.findById(targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + targetUserId));

        // Check if already following
        boolean alreadyFollowing = followingRepository.existsByFollowerUserIdAndFollowedUserId(userId, targetUserId);
        if (alreadyFollowing) {
            throw new IllegalStateException("Already following this user.");
        }

        Following following = new Following();
        following.setFollower(follower);
        following.setFollowed(followed);
        followingRepository.save(following);
    }

    @Override
    @Transactional
    public void unfollowUser(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new IllegalArgumentException("You cannot unfollow yourself.");
        }
        // Check if both users exist
        User follower = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));
        User followed = userRepository.findById(targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + targetUserId));

        boolean followOpt = followingRepository.existsByFollowerUserIdAndFollowedUserId(userId, targetUserId);
        if (!followOpt) {
            throw new IllegalStateException("Not following this user.");
        }
        followingRepository.deleteByFollowerAndFollowed(follower, followed);
    }

}