package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateFeatureRequest;
import com.ironside.challengesphere.api.response.FeatureRequestResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.FeatureRequest;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.FeatureRequestRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.RateLimitStore;
import com.ironside.challengesphere.service.EmailService;
import com.ironside.challengesphere.service.FeatureRequestService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class FeatureRequestServiceImpl implements FeatureRequestService {

    private static final Logger logger = LogManager.getLogger(FeatureRequestServiceImpl.class);
    private static final String SUPPORT_EMAIL = "ironside-dev@challengesphere.com";
    private static final int MAX_REQUESTS_PER_DAY = 5;

    private final FeatureRequestRepository featureRequestRepository;
    private final UserRepository userRepository;
    private final RateLimitStore rateLimitStore;
    private final EmailService emailService;

    public FeatureRequestServiceImpl(
            FeatureRequestRepository featureRequestRepository,
            UserRepository userRepository,
            RateLimitStore rateLimitStore,
            EmailService emailService
    ) {
        this.featureRequestRepository = featureRequestRepository;
        this.userRepository = userRepository;
        this.rateLimitStore = rateLimitStore;
        this.emailService = emailService;
    }

    @Override
    @Transactional
    public FeatureRequestResponse createFeatureRequest(
            Long userId,
            CreateFeatureRequest request
    ) throws ChallengeSphereException {
        if (userId == null) {
            throw badRequest("User id is required");
        }
        if (request == null) {
            throw badRequest("Feature request is required");
        }
        if (request.getCategory() == null) {
            throw badRequest("Feature category is required");
        }

        String message = trim(request.getMessage());
        if (message.length() < 10 || message.length() > 300) {
            throw badRequest("Feature request must be between 10 and 300 characters");
        }
        User user = userRepository.findById(userId).orElseThrow(() ->
                new ChallengeSphereException("User not found", HttpStatus.NOT_FOUND, logger));

        reserveDailyRequest(userId);

        FeatureRequest featureRequest = new FeatureRequest();
        featureRequest.setUserId(userId);
        featureRequest.setCategory(request.getCategory().name());
        featureRequest.setMessage(message);
        featureRequest.setAppVersion(trimToNull(request.getAppVersion()));
        featureRequest.setPlatform(trimToNull(request.getPlatform()));
        featureRequest.setDeviceInfo(trimToNull(request.getDeviceInfo()));
        featureRequest.setStatus("NEW");

        FeatureRequest saved = featureRequestRepository.save(featureRequest);
        emailService.sendHtmlEmail(
                SUPPORT_EMAIL,
                buildEmailSubject(saved, user),
                buildEmailHtml(saved, user)
        );
        logger.info("Created feature request id={} for userId={} category={}",
                saved.getId(), userId, saved.getCategory());

        return new FeatureRequestResponse(
                saved.getId(),
                saved.getStatus(),
                saved.getCreatedAt(),
                "Feature request received"
        );
    }

    private void reserveDailyRequest(Long userId) throws ChallengeSphereException {
        RateLimitStore.Decision decision;
        try {
            decision = rateLimitStore.consume(
                    "feature-request:daily:user:" + userId,
                    MAX_REQUESTS_PER_DAY,
                    Duration.ofDays(1)
            );
        } catch (RuntimeException unavailable) {
            logger.error("Feature request rate limit unavailable for userId={}: {}",
                    userId, unavailable.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Feature requests are temporarily unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger,
                    unavailable
            );
        }
        if (!decision.allowed()) {
            throw new ChallengeSphereException(
                    "Daily feature request limit reached",
                    HttpStatus.TOO_MANY_REQUESTS,
                    logger
            );
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String buildEmailSubject(FeatureRequest request, User user) {
        return "[ChallengeSphere Feature Request] "
                + request.getCategory()
                + " from @"
                + safeText(user.getUsername());
    }

    private String buildEmailHtml(FeatureRequest request, User user) {
        return "<div style=\"font-family:Arial,sans-serif;color:#222;line-height:1.5;\">"
                + "<h2>New ChallengeSphere feature request</h2>"
                + "<p><strong>User:</strong> @"
                + escape(user.getUsername())
                + " (ID "
                + user.getId()
                + ")</p>"
                + "<p><strong>Email:</strong> "
                + escape(user.getEmailId())
                + "</p>"
                + "<p><strong>Category:</strong> "
                + escape(request.getCategory())
                + "</p>"
                + "<p><strong>Message:</strong></p>"
                + "<blockquote style=\"border-left:4px solid #4700ec;padding-left:12px;margin-left:0;\">"
                + escape(request.getMessage())
                + "</blockquote>"
                + "<p><strong>App version:</strong> "
                + escape(request.getAppVersion())
                + "</p>"
                + "<p><strong>Platform:</strong> "
                + escape(request.getPlatform())
                + "</p>"
                + "<p><strong>Device:</strong> "
                + escape(request.getDeviceInfo())
                + "</p>"
                + "<p><strong>Request ID:</strong> "
                + request.getId()
                + "</p>"
                + "</div>";
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private ChallengeSphereException badRequest(String message) {
        return new ChallengeSphereException(message, HttpStatus.BAD_REQUEST, logger);
    }
}
