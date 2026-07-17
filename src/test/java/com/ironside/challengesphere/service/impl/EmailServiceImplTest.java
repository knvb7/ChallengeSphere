package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceImplTest {

    @Test
    void featureRequestEmailAlwaysTargetsIronsideDevelopmentInbox() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "sender@example.com");
        ReflectionTestUtils.setField(service, "mailEnabled", true);

        service.sendFeatureRequestEmail("Feature request", "<p>Requested feature</p>");

        Address[] recipients = message.getRecipients(Message.RecipientType.TO);
        assertNotNull(recipients);
        assertEquals(1, recipients.length);
        assertEquals(
                "ironside-dev@challengesphere.com",
                ((InternetAddress) recipients[0]).getAddress()
        );
        assertNull(message.getRecipients(Message.RecipientType.CC));
        assertNull(message.getRecipients(Message.RecipientType.BCC));
        verify(mailSender).send(message);
    }

    @Test
    void supportRequestEmailAlwaysTargetsIronsideDevelopmentInbox() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "sender@example.com");
        ReflectionTestUtils.setField(service, "mailEnabled", true);

        service.sendSupportRequestEmail("Support request", "<p>Broken upload</p>");

        Address[] recipients = message.getRecipients(Message.RecipientType.TO);
        assertNotNull(recipients);
        assertEquals(1, recipients.length);
        assertEquals(
                "ironside-dev@challengesphere.com",
                ((InternetAddress) recipients[0]).getAddress()
        );
        assertNull(message.getRecipients(Message.RecipientType.CC));
        assertNull(message.getRecipients(Message.RecipientType.BCC));
        verify(mailSender).send(message);
    }

    @Test
    void supportRequestEmailFailsWhenMailIsDisabled() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        ReflectionTestUtils.setField(service, "mailEnabled", false);

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.sendSupportRequestEmail(
                        "Support request",
                        "<p>Broken upload</p>"
                )
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void supportRequestEmailPropagatesSmtpFailure() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(message);
        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "sender@example.com");
        ReflectionTestUtils.setField(service, "mailEnabled", true);

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.sendSupportRequestEmail(
                        "Support request",
                        "<p>Broken upload</p>"
                )
        );

        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
        assertEquals("Failed to send support request", error.getResponseMessage());
        verify(mailSender).send(message);
    }
}
