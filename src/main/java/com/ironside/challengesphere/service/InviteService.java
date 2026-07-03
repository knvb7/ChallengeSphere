package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.CreateInviteLinkRequest;
import com.ironside.challengesphere.api.request.RedeemInviteRequest;
import com.ironside.challengesphere.api.response.ChallengeInvitesResponse;
import com.ironside.challengesphere.api.response.InviteLinkResponse;
import com.ironside.challengesphere.api.response.RedeemInviteResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface InviteService {

    /**
     * Generate a unique invite link for a challenge.
     * Only the challenge creator or an active participant can generate an invite.
     */
    InviteLinkResponse generateInviteLink(CreateInviteLinkRequest request) throws ChallengeSphereException;

    /**
     * Redeem an invite code to join a challenge.
     * Validates the code, checks expiration, usage limits, and joins the user.
     */
    RedeemInviteResponse redeemInvite(RedeemInviteRequest request) throws ChallengeSphereException;

    /**
     * Get invite link details by invite code (for preview before joining).
     */
    InviteLinkResponse getInviteDetails(String inviteCode) throws ChallengeSphereException;

    /**
     * Get all invite links for a specific challenge.
     */
    ChallengeInvitesResponse getInvitesForChallenge(Long challengeId, Long userId) throws ChallengeSphereException;

    /**
     * Deactivate an invite link. Only the creator of the invite or the challenge creator can do this.
     */
    void deactivateInvite(Long inviteId, Long userId) throws ChallengeSphereException;
}
