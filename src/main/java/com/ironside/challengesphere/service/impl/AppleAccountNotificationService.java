package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.AuthIdentity;
import com.ironside.challengesphere.repository.AuthIdentityRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AppleAccountNotificationService {

    private static final Logger logger = LogManager.getLogger(AppleAccountNotificationService.class);

    private final AppleSignInService appleSignInService;
    private final AuthIdentityRepository authIdentityRepository;
    private final AccountDeletionService accountDeletionService;
    private final JdbcTemplate jdbcTemplate;

    public AppleAccountNotificationService(
            AppleSignInService appleSignInService,
            AuthIdentityRepository authIdentityRepository,
            AccountDeletionService accountDeletionService,
            JdbcTemplate jdbcTemplate) {
        this.appleSignInService = appleSignInService;
        this.authIdentityRepository = authIdentityRepository;
        this.accountDeletionService = accountDeletionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void process(String signedPayload) throws ChallengeSphereException {
        AppleSignInService.AppleAccountEvent event =
                appleSignInService.verifyServerNotification(signedPayload);
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO challengesphere.apple_account_events (
                    jti,
                    event_type,
                    subject,
                    event_time
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (jti) DO NOTHING
                """,
                event.jti(),
                event.type(),
                event.subject(),
                event.eventTime()
        );
        if (inserted == 0) {
            return;
        }

        Optional<AuthIdentity> identity = authIdentityRepository.findByProviderAndSubject(
                "APPLE",
                event.subject()
        );
        if (identity.isEmpty()) {
            logger.info("Apple account event {} did not match an active identity", event.type());
            return;
        }

        AuthIdentity appleIdentity = identity.get();
        switch (event.type()) {
            case "email-enabled" -> {
                appleIdentity.setEmailForwardingEnabled(true);
                authIdentityRepository.save(appleIdentity);
            }
            case "email-disabled" -> {
                appleIdentity.setEmailForwardingEnabled(false);
                authIdentityRepository.save(appleIdentity);
            }
            case "consent-revoked", "account-delete", "account-deleted" ->
                    accountDeletionService.deleteAccountFromAppleNotification(
                            appleIdentity.getUser().getId()
                    );
            default -> logger.warn("Ignored unsupported Apple account event type={}", event.type());
        }
    }
}
