package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSupportTicketRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SupportTicketResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.IssuedUploadService;
import com.ironside.challengesphere.security.RateLimitStore;
import com.ironside.challengesphere.security.UploadQuotaService;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.EmailService;
import com.ironside.challengesphere.util.MediaTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportServiceImplTest {

    private UserRepository userRepository;
    private MediaUrlService mediaUrlService;
    private EmailService emailService;
    private UploadQuotaService uploadQuotaService;
    private IssuedUploadService issuedUploadService;
    private RateLimitStore rateLimitStore;
    private ContentModerationService contentModerationService;
    private ModerationEnforcementService moderationEnforcementService;
    private SupportServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        mediaUrlService = mock(MediaUrlService.class);
        emailService = mock(EmailService.class);
        uploadQuotaService = mock(UploadQuotaService.class);
        issuedUploadService = mock(IssuedUploadService.class);
        rateLimitStore = mock(RateLimitStore.class);
        contentModerationService = mock(ContentModerationService.class);
        moderationEnforcementService = mock(ModerationEnforcementService.class);
        service = new SupportServiceImpl(
                userRepository,
                mediaUrlService,
                emailService,
                uploadQuotaService,
                issuedUploadService,
                rateLimitStore,
                contentModerationService,
                moderationEnforcementService
        );
    }

    @Test
    void validTicketSendsDetailedEscapedEmailWithoutPersistence() throws Exception {
        allowSupportRequest();
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.BUG,
                "Upload fails\r\nBcc: attacker@example.com",
                "The upload fails after reaching 100%. <script>alert(\"x\")</script>",
                List.of()
        );

        SupportTicketResponse response = service.createTicket(42L, request);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendSupportRequestEmail(subject.capture(), html.capture());
        assertTrue(response.isSuccess());
        assertEquals("Support request sent", response.getMessage());
        assertEquals(
                "[ChallengeSphere Support] Bug: Upload fails Bcc: attacker@example.com",
                subject.getValue()
        );
        assertFalse(subject.getValue().contains("\r"));
        assertFalse(subject.getValue().contains("\n"));
        assertTrue(html.getValue().contains("ChallengeSphere Support Request"));
        assertTrue(html.getValue().contains("pranjal@example.com"));
        assertTrue(html.getValue().contains("The upload fails after reaching 100%."));
        assertTrue(html.getValue().contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;"));
        assertFalse(html.getValue().contains("<script>"));
        assertTrue(html.getValue().contains("1.0.0"));
        assertTrue(html.getValue().contains("android"));
        assertTrue(html.getValue().contains("Pixel 9"));
        assertTrue(html.getValue().contains("Submitted:"));
        assertTrue(html.getValue().contains("No screenshots attached."));
    }

    @Test
    void ticketWithScreenshotSendsPrivateLinkAndConsumesUploadReference() throws Exception {
        allowSupportRequest();
        String key = "support/user_42/screenshots/1721234567890_"
                + "123e4567-e89b-12d3-a456-426614174000.png";
        String privateUrl = "https://media.example.com/private/support.png?signature=secret";
        when(mediaUrlService.objectExists(key)).thenReturn(true);
        when(mediaUrlService.toReadableMediaUrl(key)).thenReturn(privateUrl);
        when(contentModerationService.scanStrict(List.of(key))).thenReturn(
                new ContentModerationService.Result(
                        ContentModerationService.Verdict.CLEAN,
                        List.of()
                )
        );
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.MEDIA_UPLOAD,
                "Upload screenshot",
                "The attached screenshot shows the failed upload.",
                List.of(key)
        );

        service.createTicket(42L, request);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendSupportRequestEmail(anyString(), html.capture());
        verify(issuedUploadService).consume(
                42L,
                List.of(key),
                IssuedUploadService.Purpose.SUPPORT_SCREENSHOT
        );
        verify(contentModerationService).scanStrict(List.of(key));
        assertTrue(html.getValue().contains(privateUrl.replace("&", "&amp;")));
        assertTrue(html.getValue().contains(key));
        assertTrue(html.getValue().contains("Open screenshot"));
    }

    @Test
    void supportScreenshotPresignedUrlUsesUserScopedKeyAndSecurityServices() throws Exception {
        when(userRepository.existsById(42L)).thenReturn(true);
        when(mediaUrlService.createUploadUrl(
                anyString(),
                eq(3600L),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                eq(1234L)
        )).thenReturn("https://uploads.example.com/presigned");
        GeneratePresignedUrlsRequest.MediaFileInfo file = new GeneratePresignedUrlsRequest.MediaFileInfo();
        file.setFileName("support.png");
        file.setMediaType(MediaTypes.SUPPORT_SCREENSHOT);
        file.setContentType(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG);
        file.setFileSizeBytes(1234L);
        GeneratePresignedUrlsRequest request = new GeneratePresignedUrlsRequest();
        request.setMediaFiles(List.of(file));

        PresignedUrlsResponse response = service.generatePresignedUrls(42L, request);

        assertTrue(response.isSuccess());
        assertEquals(1, response.getPresignedUrls().size());
        String key = response.getPresignedUrls().get(0).getFileKey();
        assertTrue(key.startsWith("support/user_42/screenshots/"));
        assertTrue(key.endsWith(".png"));
        verify(uploadQuotaService).reserve(42L, List.of(file));
        verify(issuedUploadService).issue(
                42L,
                key,
                IssuedUploadService.Purpose.SUPPORT_SCREENSHOT
        );
    }

    @Test
    void invalidMessageIsRejectedBeforeEmailDelivery() throws Exception {
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.QUESTION,
                "Question",
                "short",
                List.of()
        );

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(42L, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(emailService, never()).sendSupportRequestEmail(anyString(), anyString());
    }

    @Test
    void rawPrivateKeyFromReadableUrlFailsBeforeEmailDelivery() throws Exception {
        allowSupportRequest();
        String key = "support/user_42/screenshots/1721234567890_"
                + "123e4567-e89b-12d3-a456-426614174000.png";
        when(mediaUrlService.objectExists(key)).thenReturn(true);
        when(mediaUrlService.toReadableMediaUrl(key)).thenReturn(key);
        when(contentModerationService.scanStrict(List.of(key))).thenReturn(
                new ContentModerationService.Result(
                        ContentModerationService.Verdict.CLEAN,
                        List.of()
                )
        );
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.BUG,
                "Screenshot link",
                "The screenshot link needs to be readable.",
                List.of(key)
        );

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(42L, request)
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        verify(emailService, never()).sendSupportRequestEmail(anyString(), anyString());
    }

    @Test
    void blankScreenshotReferenceIsRejectedBeforeEmailDelivery() throws Exception {
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.BUG,
                "Blank screenshot",
                "The screenshot reference should be rejected.",
                List.of(" ")
        );

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(42L, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(emailService, never()).sendSupportRequestEmail(anyString(), anyString());
    }

    @Test
    void foreignScreenshotReferenceIsRejectedBeforeObjectLookup() throws Exception {
        allowSupportRequest();
        String foreignKey = "support/user_99/screenshots/1721234567890_"
                + "123e4567-e89b-12d3-a456-426614174000.png";
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.BUG,
                "Foreign screenshot",
                "This reference belongs to a different account.",
                List.of(foreignKey)
        );

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(42L, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(mediaUrlService, never()).objectExists(anyString());
        verify(emailService, never()).sendSupportRequestEmail(anyString(), anyString());
    }

    @Test
    void missingScreenshotObjectIsRejectedBeforeEmailDelivery() throws Exception {
        allowSupportRequest();
        String key = "support/user_42/screenshots/1721234567890_"
                + "123e4567-e89b-12d3-a456-426614174000.png";
        when(mediaUrlService.objectExists(key)).thenReturn(false);
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.BUG,
                "Missing screenshot",
                "The referenced screenshot does not exist.",
                List.of(key)
        );

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(42L, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(emailService, never()).sendSupportRequestEmail(anyString(), anyString());
    }

    @Test
    void dailyRateLimitDenialStopsEmailDelivery() throws Exception {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user()));
        when(rateLimitStore.consume(
                anyString(),
                eq(5),
                eq(Duration.ofDays(1))
        )).thenReturn(new RateLimitStore.Decision(false, 0, 120));
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.QUESTION,
                "Rate limited",
                "This support request exceeds the daily limit.",
                List.of()
        );

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(42L, request)
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        verify(emailService, never()).sendSupportRequestEmail(anyString(), anyString());
    }

    @Test
    void rateLimitStoreOutageFailsClosed() throws Exception {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user()));
        when(rateLimitStore.consume(
                anyString(),
                eq(5),
                eq(Duration.ofDays(1))
        )).thenThrow(new IllegalStateException("Redis unavailable"));
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.QUESTION,
                "Rate store unavailable",
                "This request cannot bypass unavailable protection.",
                List.of()
        );

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(42L, request)
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        verify(emailService, never()).sendSupportRequestEmail(anyString(), anyString());
    }

    @Test
    void unsafeScreenshotBansUserAndStopsEmailDelivery() throws Exception {
        allowSupportRequest();
        String key = "support/user_42/screenshots/1721234567890_"
                + "123e4567-e89b-12d3-a456-426614174000.png";
        when(mediaUrlService.objectExists(key)).thenReturn(true);
        when(contentModerationService.scanStrict(List.of(key))).thenReturn(
                new ContentModerationService.Result(
                        ContentModerationService.Verdict.BLOCK_AND_BAN,
                        List.of(new ContentModerationService.Finding(
                                key,
                                "adult",
                                "VERY_LIKELY"
                        ))
                )
        );
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.OTHER,
                "Unsafe screenshot",
                "This attachment violates the content policy.",
                List.of(key)
        );

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(42L, request)
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(moderationEnforcementService).banUser(
                42L,
                "Content policy violation in support attachment"
        );
        verify(emailService, never()).sendSupportRequestEmail(anyString(), anyString());
    }

    @Test
    void videoSupportUploadIsRejectedBeforeQuotaReservation() throws Exception {
        GeneratePresignedUrlsRequest.MediaFileInfo file = new GeneratePresignedUrlsRequest.MediaFileInfo();
        file.setFileName("support.mp4");
        file.setMediaType(MediaTypes.SUPPORT_SCREENSHOT);
        file.setContentType(GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4);
        file.setFileSizeBytes(1234L);
        GeneratePresignedUrlsRequest request = new GeneratePresignedUrlsRequest();
        request.setMediaFiles(List.of(file));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.generatePresignedUrls(42L, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(uploadQuotaService, never()).reserve(eq(42L), eq(List.of(file)));
    }

    @Test
    void smtpFailureFromSupportEmailIsPropagated() throws Exception {
        allowSupportRequest();
        ChallengeSphereException smtpFailure = new ChallengeSphereException(
                "Failed to send support request",
                HttpStatus.BAD_GATEWAY,
                null
        );
        doThrow(smtpFailure).when(emailService)
                .sendSupportRequestEmail(anyString(), anyString());
        CreateSupportTicketRequest request = request(
                CreateSupportTicketRequest.IssueType.BUG,
                "SMTP failure",
                "The mail provider rejected this support request.",
                List.of()
        );

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(42L, request)
        );

        assertSame(smtpFailure, error);
    }

    private void allowSupportRequest() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user()));
        when(rateLimitStore.consume(
                anyString(),
                eq(5),
                eq(Duration.ofDays(1))
        )).thenReturn(new RateLimitStore.Decision(true, 4, 0));
    }

    private CreateSupportTicketRequest request(
            CreateSupportTicketRequest.IssueType issueType,
            String subject,
            String message,
            List<String> screenshotKeys
    ) {
        CreateSupportTicketRequest request = new CreateSupportTicketRequest();
        request.setIssueType(issueType);
        request.setSubject(subject);
        request.setMessage(message);
        request.setAppVersion("1.0.0");
        request.setPlatform("android");
        request.setDeviceInfo("Pixel 9");
        request.setScreenshotKeys(screenshotKeys);
        return request;
    }

    private User user() {
        User user = new User();
        user.setId(42L);
        user.setUsername("pranjal");
        user.setEmailId("pranjal@example.com");
        return user;
    }
}
