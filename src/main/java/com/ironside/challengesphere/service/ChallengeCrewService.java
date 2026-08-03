package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.response.ChallengeCrewRecapResponse;
import com.ironside.challengesphere.api.response.ChallengeCrewStatusResponse;
import com.ironside.challengesphere.api.response.ChallengeRematchResponse;
import com.ironside.challengesphere.api.response.PeerNudgeResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface ChallengeCrewService {
    ChallengeCrewStatusResponse getStatus(Long challengeId, Long actorUserId) throws ChallengeSphereException;

    ChallengeCrewStatusResponse startTogether(Long challengeId, Long actorUserId) throws ChallengeSphereException;

    ChallengeCrewStatusResponse setPeerReminders(Long challengeId, Long actorUserId, boolean enabled)
            throws ChallengeSphereException;

    PeerNudgeResponse sendPeerNudge(Long challengeId, Long actorUserId, Long recipientUserId)
            throws ChallengeSphereException;

    ChallengeCrewRecapResponse getRecap(Long challengeId, Long actorUserId) throws ChallengeSphereException;

    ChallengeRematchResponse rematch(Long challengeId, Long actorUserId) throws ChallengeSphereException;

    void requireStarted(Long challengeId) throws ChallengeSphereException;
}
