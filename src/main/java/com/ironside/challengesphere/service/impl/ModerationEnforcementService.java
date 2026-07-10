package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

@Service
public class ModerationEnforcementService {

    private static final Logger logger = LogManager.getLogger(ModerationEnforcementService.class);

    private final UserRepository userRepository;
    private final AuthTokenStore authTokenStore;

    public ModerationEnforcementService(
            UserRepository userRepository,
            AuthTokenStore authTokenStore) {
        this.userRepository = userRepository;
        this.authTokenStore = authTokenStore;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void banUser(Long userId, String reason) throws ChallengeSphereException {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "User not found with id: " + userId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));
        user.setActive(false);
        user.setBannedAt(new Timestamp(System.currentTimeMillis()));
        user.setBanReason(reason);
        userRepository.save(user);
        authTokenStore.revokeAllForUser(userId);
    }
}
