package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger logger = LogManager.getLogger(EmailServiceImpl.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@challengesphere.com}")
    private String fromAddress;

    @Value("${app.frontend.base-url:https://challengesphere.com}")
    private String frontendBaseUrl;

    @Value("${app.password-reset.base-url:myapp://reset-password}")
    private String passwordResetBaseUrl;

    @Value("${app.mail.enabled:true}")
    private boolean mailEnabled;

    public EmailServiceImpl(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    @Async("cacheExecutor")
    public void sendWelcomeEmail(User user) {
        if (user == null || user.getEmailId() == null || user.getEmailId().isBlank()) {
            logger.warn("Skipping welcome email, missing email address for user");
            return;
        }
        String subject = "Welcome to ChallengeSphere, " + user.getUsername() + "!";
        String html = "<div style=\"font-family:Arial,sans-serif;color:#222;\">"
                + "<h2>Welcome to ChallengeSphere, " + escape(user.getUsername()) + "!</h2>"
                + "<p>We're excited to have you. Jump in, create challenges, and earn Glory.</p>"
                + "<p><a href=\"" + frontendBaseUrl + "\">Start exploring</a></p>"
                + "<p>— The ChallengeSphere Team</p>"
                + "</div>";
        sendHtml(user.getEmailId(), subject, html);
    }

    @Override
    @Async("cacheExecutor")
    public void sendPasswordResetEmail(User user, String resetToken) {
        if (user == null || user.getEmailId() == null || user.getEmailId().isBlank()) {
            logger.warn("Skipping password reset email, missing email address");
            return;
        }
        String resetUrl = passwordResetBaseUrl + "?token=" + resetToken;
        String subject = "Reset your ChallengeSphere password";
        String html = "<div style=\"font-family:Arial,sans-serif;color:#222;\">"
                + "<h2>Password reset requested</h2>"
                + "<p>Hi " + escape(user.getUsername()) + ", click below to reset your password. This link expires in 30 minutes.</p>"
                + "<p><a href=\"" + resetUrl + "\">Reset password</a></p>"
                + "<p>If you didn't request this, ignore this email.</p>"
                + "</div>";
        sendHtml(user.getEmailId(), subject, html);
    }

    @Override
    @Async("cacheExecutor")
    public void sendNotificationDigest(User user, List<Notification> notifications) {
        if (user == null || user.getEmailId() == null || user.getEmailId().isBlank()) {
            return;
        }
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        String items = notifications.stream()
                .limit(25)
                .map(n -> "<li><strong>" + escape(n.getType()) + "</strong>: " + escape(n.getMessage()) + "</li>")
                .collect(Collectors.joining());
        String subject = "Your ChallengeSphere digest — " + notifications.size() + " update(s)";
        String html = "<div style=\"font-family:Arial,sans-serif;color:#222;\">"
                + "<h2>Your latest activity</h2>"
                + "<ul>" + items + "</ul>"
                + "<p><a href=\"" + frontendBaseUrl + "/notifications\">See all</a></p>"
                + "</div>";
        sendHtml(user.getEmailId(), subject, html);
    }

    @Override
    @Async("cacheExecutor")
    public void sendPlainEmail(String to, String subject, String body) {
        if (!mailEnabled) {
            logger.info("Mail disabled, skipping plain email to={}, subject={}", to, subject);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            logger.info("Sent plain email to={}, subject={}", to, subject);
        } catch (MailException e) {
            logger.error("Failed to send plain email to={}: {}", to, e.getMessage());
        }
    }

    private void sendHtml(String to, String subject, String html) {
        if (!mailEnabled) {
            logger.info("Mail disabled, skipping HTML email to={}, subject={}", to, subject);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            logger.info("Sent HTML email to={}, subject={}", to, subject);
        } catch (MessagingException | MailException e) {
            logger.error("Failed to send HTML email to={}: {}", to, e.getMessage());
        }
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
}
