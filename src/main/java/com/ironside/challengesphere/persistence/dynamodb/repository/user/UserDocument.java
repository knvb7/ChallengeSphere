package com.ironside.challengesphere.persistence.dynamodb.repository.user;

import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.util.UserGender;
import com.ironside.challengesphere.util.UserRole;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/** Persisted user payload that intentionally includes the password hash. */
public record UserDocument(
        String id,
        String legacyId,
        String emailId,
        String phone,
        String username,
        String name,
        String hashedPassword,
        String googleId,
        Timestamp createdAt,
        Integer genuinenessScore,
        Boolean active,
        Integer challengesCreated,
        Integer challengesAccepted,
        Integer followers,
        Integer following,
        String profilePicUrl,
        Integer premiumUser,
        UserRole role,
        Integer notifications,
        String bio,
        LocalDate dateOfBirth,
        Integer age,
        UserGender gender,
        String country,
        List<String> interests,
        Timestamp lastLoginAt,
        Timestamp bannedAt,
        String banReason,
        Timestamp deletedAt
) {

    public static UserDocument from(User user) {
        return new UserDocument(
                user.getId(),
                user.getLegacyId(),
                user.getEmailId(),
                user.getPhone(),
                user.getUsername(),
                user.getName(),
                user.getHashedPassword(),
                user.getGoogleId(),
                user.getCreatedAt(),
                user.getGenuinenessScore(),
                user.getActive(),
                user.getChallengesCreated(),
                user.getChallengesAccepted(),
                user.getFollowers(),
                user.getFollowing(),
                user.getProfilePicUrl(),
                user.getPremiumUser(),
                user.getRole(),
                user.getNotifications(),
                user.getBio(),
                user.getDateOfBirth(),
                user.getAge(),
                user.getGender(),
                user.getCountry(),
                user.getInterests(),
                user.getLastLoginAt(),
                user.getBannedAt(),
                user.getBanReason(),
                user.getDeletedAt()
        );
    }

    public User toModel() {
        User user = new User();
        user.setId(id);
        user.setLegacyId(legacyId);
        user.setEmailId(emailId);
        user.setPhone(phone);
        user.setUsername(username);
        user.setName(name);
        user.setHashedPassword(hashedPassword);
        user.setGoogleId(googleId);
        user.setCreatedAt(createdAt);
        user.setGenuinenessScore(genuinenessScore);
        user.setActive(active);
        user.setChallengesCreated(challengesCreated);
        user.setChallengesAccepted(challengesAccepted);
        user.setFollowers(followers);
        user.setFollowing(following);
        user.setProfilePicUrl(profilePicUrl);
        user.setPremiumUser(premiumUser);
        user.setRole(role);
        user.setNotifications(notifications);
        user.setBio(bio);
        user.setDateOfBirth(dateOfBirth);
        user.setAge(age);
        user.setGender(gender);
        user.setCountry(country);
        user.setInterests(interests);
        user.setLastLoginAt(lastLoginAt);
        user.setBannedAt(bannedAt);
        user.setBanReason(banReason);
        user.setDeletedAt(deletedAt);
        return user;
    }
}
