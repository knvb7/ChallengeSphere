package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.StravaConnectRequest;
import com.ironside.challengesphere.api.response.StravaAuthorizeUrlResponse;
import com.ironside.challengesphere.api.response.StravaConnectionResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface StravaIntegrationService {
    StravaAuthorizeUrlResponse createAuthorizationUrl(Long userId, String redirectUri) throws ChallengeSphereException;
    StravaConnectionResponse connect(Long userId, StravaConnectRequest request) throws ChallengeSphereException;
    StravaConnectionResponse getConnection(Long userId) throws ChallengeSphereException;
    StravaConnectionResponse sync(Long userId) throws ChallengeSphereException;
    void disconnect(Long userId);
}
