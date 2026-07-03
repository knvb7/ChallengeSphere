package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.service.SmsService;
import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SmsServiceImpl implements SmsService {

    private static final Logger logger = LogManager.getLogger(SmsServiceImpl.class);

    @Value("${twilio.accountSid:}")
    private String accountSid;

    @Value("${twilio.authToken:}")
    private String authToken;

    @Value("${twilio.verifyServiceSid:}")
    private String verifyServiceSid;

    @Value("${twilio.enabled:true}")
    private boolean twilioEnabled;

    private boolean initialized = false;

    @PostConstruct
    public void init() {
        if (!twilioEnabled) {
            logger.info("Twilio disabled via config, SMS OTP will be no-op");
            return;
        }
        if (accountSid == null || accountSid.isBlank()
                || authToken == null || authToken.isBlank()
                || verifyServiceSid == null || verifyServiceSid.isBlank()) {
            logger.warn("Twilio credentials not fully configured, SmsService will reject OTP requests");
            return;
        }
        try {
            Twilio.init(accountSid, authToken);
            initialized = true;
            logger.info("Twilio Verify client initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize Twilio client");
        }
    }

    @Override
    public String sendOtp(String phoneE164) throws ChallengeSphereException {
        ensureReady();
        String normalized = normalize(phoneE164);
        try {
            Verification verification = Verification.creator(verifyServiceSid, normalized, "sms").create();
            logger.info("OTP dispatch accepted with status={}", verification.getStatus());
            return verification.getSid();
        } catch (ApiException e) {
            logger.error("Twilio OTP dispatch failed");
            throw new ChallengeSphereException("Failed to send verification code: " + e.getMessage(),
                    HttpStatus.BAD_GATEWAY, logger, e);
        }
    }

    @Override
    public boolean verifyOtp(String phoneE164, String code) throws ChallengeSphereException {
        ensureReady();
        if (code == null || code.isBlank()) {
            throw new ChallengeSphereException("Verification code is required", HttpStatus.BAD_REQUEST, logger);
        }
        String normalized = normalize(phoneE164);
        try {
            VerificationCheck check = VerificationCheck.creator(verifyServiceSid)
                    .setTo(normalized)
                    .setCode(code)
                    .create();
            logger.info("OTP verification returned status={}", check.getStatus());
            return "approved".equalsIgnoreCase(check.getStatus());
        } catch (ApiException e) {
            logger.error("Twilio OTP verification failed");
            throw new ChallengeSphereException("Failed to verify code: " + e.getMessage(),
                    HttpStatus.BAD_GATEWAY, logger, e);
        }
    }

    private void ensureReady() throws ChallengeSphereException {
        if (!twilioEnabled) {
            throw new ChallengeSphereException("SMS verification is disabled on this environment",
                    HttpStatus.SERVICE_UNAVAILABLE, logger);
        }
        if (!initialized) {
            throw new ChallengeSphereException("SMS verification is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE, logger);
        }
    }

    private String normalize(String phone) throws ChallengeSphereException {
        if (phone == null || phone.isBlank()) {
            throw new ChallengeSphereException("Phone number is required", HttpStatus.BAD_REQUEST, logger);
        }
        String trimmed = phone.trim().replaceAll("\\s+", "");
        if (!trimmed.startsWith("+")) {
            trimmed = "+" + trimmed;
        }
        if (!trimmed.matches("^\\+[0-9]{8,20}$")) {
            throw new ChallengeSphereException("Invalid phone number format, expected E.164",
                    HttpStatus.BAD_REQUEST, logger);
        }
        return trimmed;
    }
}
