package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.HevyConnectRequest;
import com.ironside.challengesphere.api.response.HevyConnectionResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface HevyIntegrationService {
    HevyConnectionResponse connect(Long userId, HevyConnectRequest request) throws ChallengeSphereException;
    HevyConnectionResponse getConnection(Long userId) throws ChallengeSphereException;
    HevyConnectionResponse sync(Long userId) throws ChallengeSphereException;
    void disconnect(Long userId);
}
