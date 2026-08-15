package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.response.SubscriptionStatusResponse;
import com.ironside.challengesphere.api.response.PaymentHistoryResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface SubscriptionService {
    SubscriptionStatusResponse getStatus(Long userId) throws ChallengeSphereException;

    SubscriptionStatusResponse sync(Long userId) throws ChallengeSphereException;

    PaymentHistoryResponse getPaymentHistory(Long userId, int page, int pageSize)
            throws ChallengeSphereException;

    void processRevenueCatWebhook(byte[] rawBody, String authorization, String signature)
            throws ChallengeSphereException;
}
