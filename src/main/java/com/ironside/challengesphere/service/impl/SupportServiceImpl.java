package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSupportTicketRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SupportTicketResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.EmailService;
import com.ironside.challengesphere.service.SupportService;
import com.ironside.challengesphere.util.MediaTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SupportServiceImpl implements SupportService {

    private static final Logger logger = LogManager.getLogger(SupportServiceImpl.class);
    private static final String SUPPORT_EMAIL = "support@ironside.com";
    private static final int MAX_SCREENSHOTS = 3;
    private static final long UPLOAD_EXPIRY_SECONDS = 3600L;

    private final UserRepository userRepository;
    private final MediaUrlService mediaUrlService;
    private final EmailService emailService;

    public SupportServiceImpl(
            UserRepository userRepository,
            MediaUrlService mediaUrlService,
            EmailService emailService
    ) {
        this.userRepository = userRepository;
        this.mediaUrlService = mediaUrlService;
        this.emailService = emailService;
    }

    @Override
    public PresignedUrlsResponse generatePresignedUrls(
            Long userId,
            GeneratePresignedUrlsRequest request
    ) throws ChallengeSphereException {
        List<GeneratePresignedUrlsRequest.MediaFileInfo> mediaFiles =
                request == null ? null : request.getMediaFiles();

        validateSupportUploadFiles(mediaFiles);
        if (userId == null) {
            throw badRequest("User id is required");
        }
        if (!userRepository.existsById(userId)) {
            throw new ChallengeSphereException("User not found", HttpStatus.NOT_FOUND, logger);
        }

        List<PresignedUrlsResponse.PresignedUrlInfo> presignedUrls = new ArrayList<>();
        for (GeneratePresignedUrlsRequest.MediaFileInfo file : mediaFiles) {
            String fileKey = buildScreenshotKey(userId, file.getFileName());
            presignedUrls.add(new PresignedUrlsResponse.PresignedUrlInfo(
                    file.getFileName(),
                    file.getMediaType().name(),
                    file.getContentType().getMimeType(),
                    mediaUrlService.createUploadUrl(fileKey, UPLOAD_EXPIRY_SECONDS, file.getContentType()),
                    fileKey,
                    UPLOAD_EXPIRY_SECONDS
            ));
        }

        logger.info("Generated {} support screenshot upload URL(s) for userId={}", presignedUrls.size(), userId);
        return PresignedUrlsResponse.success(userId, presignedUrls);
    }

    @Override
    public SupportTicketResponse createTicket(
            Long userId,
            CreateSupportTicketRequest request
    ) throws ChallengeSphereException {
        if (userId == null) {
            throw badRequest("User id is required");
        }
        validateTicketRequest(request);

        User user = userRepository.findById(userId).orElseThrow(() ->
                new ChallengeSphereException("User not found", HttpStatus.NOT_FOUND, logger));

        List<String> screenshotKeys = request.getScreenshotKeys() == null
                ? List.of()
                : request.getScreenshotKeys().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();

        for (String key : screenshotKeys) {
            validateScreenshotKey(userId, key);
        }

        String supportSubject = "[ChallengeSphere Support] "
                + request.getIssueType().getLabel()
                + ": "
                + safeSubjectText(request.getSubject());
        String html = buildSupportEmailHtml(user, request, screenshotKeys);
        emailService.sendHtmlEmail(SUPPORT_EMAIL, supportSubject, html);

        logger.info("Submitted support request for userId={} issueType={}", userId, request.getIssueType());
        return new SupportTicketResponse(true, "Support request sent");
    }

    private void validateSupportUploadFiles(
            List<GeneratePresignedUrlsRequest.MediaFileInfo> mediaFiles
    ) throws ChallengeSphereException {
        if (mediaFiles == null || mediaFiles.isEmpty()) {
            throw badRequest("At least one screenshot is required");
        }
        if (mediaFiles.size() > MAX_SCREENSHOTS) {
            throw badRequest("A support request can include at most 3 screenshots");
        }

        for (GeneratePresignedUrlsRequest.MediaFileInfo file : mediaFiles) {
            if (file == null
                    || !StringUtils.hasText(file.getFileName())
                    || file.getMediaType() != MediaTypes.SUPPORT_SCREENSHOT
                    || !isImageContentType(file.getContentType())) {
                throw badRequest("Unsupported support screenshot upload");
            }
        }
    }

    private void validateTicketRequest(CreateSupportTicketRequest request) throws ChallengeSphereException {
        if (request == null) {
            throw badRequest("Support request is required");
        }
        if (request.getIssueType() == null) {
            throw badRequest("Issue type is required");
        }
        String subject = trim(request.getSubject());
        if (subject.length() < 3 || subject.length() > 120) {
            throw badRequest("Subject must be between 3 and 120 characters");
        }
        String message = trim(request.getMessage());
        if (message.length() < 10 || message.length() > 4000) {
            throw badRequest("Message must be between 10 and 4000 characters");
        }
        if (request.getScreenshotKeys() != null && request.getScreenshotKeys().size() > MAX_SCREENSHOTS) {
            throw badRequest("A support request can include at most 3 screenshots");
        }
    }

    private void validateScreenshotKey(Long userId, String key) throws ChallengeSphereException {
        String prefix = supportPrefix(userId);
        if (!StringUtils.hasText(key)
                || key.startsWith("http://")
                || key.startsWith("https://")
                || !key.startsWith(prefix)
                || key.contains("..")
                || key.contains("?")) {
            throw badRequest("Invalid support screenshot reference");
        }
    }

    private String buildScreenshotKey(Long userId, String fileName) {
        return supportPrefix(userId)
                + Instant.now().toEpochMilli()
                + "_"
                + UUID.randomUUID()
                + getFileExtension(fileName);
    }

    private String supportPrefix(Long userId) {
        return "support/user_" + userId + "/screenshots/";
    }

    private String getFileExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return ".jpg";
        }

        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ".jpg";
        }

        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case ".jpg", ".jpeg", ".png", ".gif" -> extension;
            default -> ".jpg";
        };
    }

    private boolean isImageContentType(GeneratePresignedUrlsRequest.ContentType contentType) {
        return contentType == GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG
                || contentType == GeneratePresignedUrlsRequest.ContentType.IMAGE_PNG
                || contentType == GeneratePresignedUrlsRequest.ContentType.IMAGE_GIF;
    }

    private String buildSupportEmailHtml(
            User user,
            CreateSupportTicketRequest request,
            List<String> screenshotKeys
    ) {
        String screenshots = screenshotKeys.isEmpty()
                ? "<p>No screenshots attached.</p>"
                : "<ol>" + screenshotKeys.stream()
                .map(key -> "<li><a href=\"" + escapeAttribute(mediaUrlService.toReadableMediaUrl(key)) + "\">"
                        + "Open screenshot</a><br><code>" + escape(key) + "</code></li>")
                .collect(Collectors.joining()) + "</ol>";

        return "<div style=\"font-family:Arial,sans-serif;color:#222;line-height:1.5;\">"
                + "<h2>ChallengeSphere Support Request</h2>"
                + "<p><strong>Issue type:</strong> " + escape(request.getIssueType().getLabel()) + "</p>"
                + "<p><strong>Subject:</strong> " + escape(trim(request.getSubject())) + "</p>"
                + "<h3>User</h3>"
                + "<p>"
                + "<strong>ID:</strong> " + escape(String.valueOf(user.getId())) + "<br>"
                + "<strong>Username:</strong> " + escape(user.getUsername()) + "<br>"
                + "<strong>Email:</strong> " + escape(user.getEmailId())
                + "</p>"
                + "<h3>App Context</h3>"
                + "<p>"
                + "<strong>Version:</strong> " + escape(defaultText(request.getAppVersion())) + "<br>"
                + "<strong>Platform:</strong> " + escape(defaultText(request.getPlatform())) + "<br>"
                + "<strong>Device:</strong> " + escape(defaultText(request.getDeviceInfo())) + "<br>"
                + "<strong>Submitted:</strong> " + escape(Instant.now().toString())
                + "</p>"
                + "<h3>Message</h3>"
                + "<p style=\"white-space:pre-wrap;\">" + escape(trim(request.getMessage())) + "</p>"
                + "<h3>Screenshots</h3>"
                + screenshots
                + "</div>";
    }

    private String defaultText(String value) {
        return StringUtils.hasText(value) ? value.trim() : "Not provided";
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeSubjectText(String value) {
        return trim(value).replaceAll("[\\r\\n]+", " ");
    }

    private String escape(String in) {
        if (in == null) {
            return "";
        }
        return in.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String escapeAttribute(String in) {
        return escape(in).replace("'", "&#39;");
    }

    private ChallengeSphereException badRequest(String message) {
        return new ChallengeSphereException(message, HttpStatus.BAD_REQUEST, logger);
    }
}
