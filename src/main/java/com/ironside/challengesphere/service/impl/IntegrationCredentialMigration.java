package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.UserIntegration;
import com.ironside.challengesphere.repository.UserIntegrationRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
public class IntegrationCredentialMigration implements ApplicationRunner {

    private final UserIntegrationRepository repository;
    private final IntegrationCredentialCipher cipher;

    public IntegrationCredentialMigration(
            UserIntegrationRepository repository,
            IntegrationCredentialCipher cipher
    ) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        run();
    }

    @Transactional
    public void run() {
        List<UserIntegration> changed = new ArrayList<>();
        for (UserIntegration integration : repository.findAll()) {
            boolean encrypted = encryptLegacyCredentials(integration);
            if (encrypted) {
                changed.add(integration);
            }
        }
        if (!changed.isEmpty()) {
            repository.saveAll(changed);
        }
    }

    private boolean encryptLegacyCredentials(UserIntegration integration) {
        boolean changed = false;
        if (hasPlaintext(integration.getAccessToken())) {
            integration.setAccessToken(cipher.encrypt(
                    integration.getUserId(),
                    integration.getProvider(),
                    "access_token",
                    integration.getAccessToken()
            ));
            changed = true;
        }
        if (hasPlaintext(integration.getRefreshToken())) {
            integration.setRefreshToken(cipher.encrypt(
                    integration.getUserId(),
                    integration.getProvider(),
                    "refresh_token",
                    integration.getRefreshToken()
            ));
            changed = true;
        }
        return changed;
    }

    private boolean hasPlaintext(String value) {
        return value != null && !value.isBlank() && !cipher.isEncrypted(value);
    }
}
