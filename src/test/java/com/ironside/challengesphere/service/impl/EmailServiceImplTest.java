package com.ironside.challengesphere.service.impl;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
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
}
