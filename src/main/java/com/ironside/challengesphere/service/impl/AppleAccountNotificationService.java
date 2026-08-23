package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.AuthIdentity;
import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore;
import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore.AppleAccountEventClaim;
import com.ironside.challengesphere.repository.AuthIdentityRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AppleAccountNotificationService {

    private static final Logger logger = LogManager.getLogger(AppleAccountNotificationService.class);

    private final AppleSignInService appleSignInService;
    private final AuthIdentityRepository authIdentityRepository;
    private final AccountDeletionService accountDeletionService;
    private final DynamoOperationalStore operationalStore;

    public AppleAccountNotificationService(
            AppleSignInService appleSignInService,
            AuthIdentityRepository authIdentityRepository,
            AccountDeletionService accountDeletionService,
            DynamoOperationalStore operationalStore) {
        this.appleSignInService = appleSignInService;
        this.authIdentityRepository = authIdentityRepository;
        this.accountDeletionService = accountDeletionService;
        this.operationalStore = operationalStore;
    }

    public void process(String signedPayload) throws ChallengeSphereException {
        AppleSignInService.AppleAccountEvent event =
                appleSignInService.verifyServerNotification(signedPayload);
        Optional<AppleAccountEventClaim> claim = operationalStore.claimAppleAccountEvent(
                event.jti(),
                event.type(),
                event.subject(),
                event.eventTime()
        );
        if (claim.isEmpty()) {
            return;
        }

        try {
            Optional<AuthIdentity> identity = authIdentityRepository.findByProviderAndSubject(
                    "APPLE",
                    event.subject()
            );
            if (identity.isEmpty()) {
                logger.info("Apple account event {} did not match an active identity", event.type());
            } else {
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
                    default -> logger.warn(
                            "Ignored unsupported Apple account event type={}", event.type()
                    );
                }
            }
            if (!operationalStore.completeAppleAccountEvent(claim.get())) {
                logger.warn("Apple account event completion lost its claim jti={}", event.jti());
            }
        } catch (ChallengeSphereException | RuntimeException failure) {
            operationalStore.retryAppleAccountEvent(claim.get(), failure);
            throw failure;
        }
    }
}
