package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger logger = LogManager.getLogger(EmailServiceImpl.class);
    private static final String FEATURE_REQUEST_RECIPIENT =
            "ironside-dev@challengesphere.com";

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@challengesphere.com}")
    private String fromAddress;

    @Value("${app.mail.enabled:true}")
    private boolean mailEnabled;

    public EmailServiceImpl(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void sendFeatureRequestEmail(String subject, String html)
            throws ChallengeSphereException {
        if (!mailEnabled) {
            throw new ChallengeSphereException(
                    "Email delivery is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger
            );
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(FEATURE_REQUEST_RECIPIENT);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            logger.info("Sent feature request email");
        } catch (MessagingException | MailException e) {
            throw new ChallengeSphereException(
                    "Failed to send feature request",
                    HttpStatus.BAD_GATEWAY,
                    logger,
                    e
            );
        }
    }

}
