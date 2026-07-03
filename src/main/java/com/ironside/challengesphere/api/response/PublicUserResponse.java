package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ironside.challengesphere.model.User;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.util.List;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublicUserResponse {
    private Long id;
    private String username;
    private Timestamp createdAt;
    private Integer genuinenessScore;
    private Boolean active;
    private Integer challengesCreated;
    private Integer challengesAccepted;
    private Integer followers;
    private Integer following;
    private String profilePicUrl;
    private Integer premiumUser;
    private String bio;
    private List<String> interests;
    private Integer submissionsCount;

    @JsonProperty("genuinityScore")
    public Integer getGenuinityScore() {
        return genuinenessScore;
    }

    public static PublicUserResponse from(User user) {
        if (user == null) {
            return null;
        }
        PublicUserResponse response = new PublicUserResponse();
        populate(response, user);
        return response;
    }

    public static List<PublicUserResponse> fromUsers(List<User> users) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        return users.stream()
                .map(PublicUserResponse::from)
                .toList();
    }

    protected static void populate(PublicUserResponse response, User user) {
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setCreatedAt(user.getCreatedAt());
        response.setGenuinenessScore(user.getGenuinenessScore());
        response.setActive(user.getActive());
        response.setChallengesCreated(user.getChallengesCreated());
        response.setChallengesAccepted(user.getChallengesAccepted());
        response.setFollowers(user.getFollowers());
        response.setFollowing(user.getFollowing());
        response.setProfilePicUrl(user.getProfilePicUrl());
        response.setPremiumUser(user.getPremiumUser());
        response.setBio(user.getBio());
        response.setInterests(user.getInterests() == null ? List.of() : List.copyOf(user.getInterests()));
        response.setSubmissionsCount(user.getSubmissionsCount());
    }
}
