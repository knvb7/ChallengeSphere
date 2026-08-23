package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.response.ChallengeCrewRecapResponse;
import com.ironside.challengesphere.api.response.ChallengeCrewStatusResponse;
import com.ironside.challengesphere.api.response.ChallengeRematchResponse;
import com.ironside.challengesphere.api.response.PeerNudgeResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface ChallengeCrewService {
    ChallengeCrewStatusResponse getStatus(String challengeId, String actorUserId) throws ChallengeSphereException;

    ChallengeCrewStatusResponse startTogether(String challengeId, String actorUserId) throws ChallengeSphereException;

    ChallengeCrewStatusResponse setPeerReminders(String challengeId, String actorUserId, boolean enabled)
            throws ChallengeSphereException;

    PeerNudgeResponse sendPeerNudge(String challengeId, String actorUserId, String recipientUserId)
            throws ChallengeSphereException;

    ChallengeCrewRecapResponse getRecap(String challengeId, String actorUserId) throws ChallengeSphereException;

    ChallengeRematchResponse rematch(String challengeId, String actorUserId) throws ChallengeSphereException;

    void requireStarted(String challengeId) throws ChallengeSphereException;
}
