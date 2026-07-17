package com.ironside.challengesphere.service;

import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface EmailService {

    void sendFeatureRequestEmail(String subject, String html) throws ChallengeSphereException;

    void sendSupportRequestEmail(String subject, String html) throws ChallengeSphereException;
}
