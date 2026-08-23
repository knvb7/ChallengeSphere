package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.CreateFeatureRequest;
import com.ironside.challengesphere.api.response.FeatureRequestResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface FeatureRequestService {
    FeatureRequestResponse createFeatureRequest(
            String userId,
            CreateFeatureRequest request
    ) throws ChallengeSphereException;
}
