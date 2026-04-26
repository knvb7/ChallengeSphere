package com.ironside.challengesphere.service;

import com.ironside.challengesphere.exception.ChallengeSphereException;

public interface SmsService {

    /**
     * Starts a Twilio Verify flow by sending an OTP over SMS to the given E.164 phone number.
     * Returns the Verify SID for tracking.
     */
    String sendOtp(String phoneE164) throws ChallengeSphereException;

    /**
     * Checks a user-submitted OTP against the Twilio Verify service.
     * Returns true iff Twilio reports status == "approved".
     */
    boolean verifyOtp(String phoneE164, String code) throws ChallengeSphereException;
}
