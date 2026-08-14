package com.ironside.challengesphere.api.response;

import lombok.Data;

@Data
public class ChallengeFavoriteResponse {
    private Long challengeId;
    private boolean favorite;

    public static ChallengeFavoriteResponse of(Long challengeId, boolean favorite) {
        ChallengeFavoriteResponse response = new ChallengeFavoriteResponse();
        response.setChallengeId(challengeId);
        response.setFavorite(favorite);
        return response;
    }
}
