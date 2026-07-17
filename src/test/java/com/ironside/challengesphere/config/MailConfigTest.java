package com.ironside.challengesphere.config;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailConfigTest {

    private static final String PARAMETER_NAME =
            "/challengesphere/prod/mail-app-password";

    @Test
    void enabledMailReadsPasswordFromEncryptedParameter() {
        SsmSecureParameterReader parameterReader = mock(SsmSecureParameterReader.class);
        when(parameterReader.read(PARAMETER_NAME)).thenReturn("generated-app-password");

        JavaMailSenderImpl sender = new MailConfig(parameterReader).javaMailSender(
                true,
                "smtp.gmail.com",
                587,
                "mihir.dalal.0911@gmail.com",
                "",
                PARAMETER_NAME,
                true,
                true,
                10000,
                10000,
                10000
        );

        assertEquals("smtp.gmail.com", sender.getHost());
        assertEquals(587, sender.getPort());
        assertEquals("mihir.dalal.0911@gmail.com", sender.getUsername());
        assertEquals("generated-app-password", sender.getPassword());
        assertEquals("true", sender.getJavaMailProperties().getProperty("mail.smtp.auth"));
        assertEquals("true", sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
        verify(parameterReader).read(PARAMETER_NAME);
    }

    @Test
    void disabledMailDoesNotReadEncryptedParameter() {
        SsmSecureParameterReader parameterReader = mock(SsmSecureParameterReader.class);

        JavaMailSenderImpl sender = new MailConfig(parameterReader).javaMailSender(
                false,
                "smtp.gmail.com",
                587,
                "",
                "",
                PARAMETER_NAME,
                true,
                true,
                10000,
                10000,
                10000
        );

        assertEquals("", sender.getPassword());
        verify(parameterReader, never()).read(PARAMETER_NAME);
    }

    @Test
    void enabledMailFailsFastWhenNoPasswordSourceIsConfigured() {
        SsmSecureParameterReader parameterReader = mock(SsmSecureParameterReader.class);

        assertThrows(
                IllegalStateException.class,
                () -> new MailConfig(parameterReader).javaMailSender(
                        true,
                        "smtp.gmail.com",
                        587,
                        "mihir.dalal.0911@gmail.com",
                        "",
                        "",
                        true,
                        true,
                        10000,
                        10000,
                        10000
                )
        );
    }

    @Test
    void localInlinePasswordDoesNotReadEncryptedParameter() {
        SsmSecureParameterReader parameterReader = mock(SsmSecureParameterReader.class);

        JavaMailSenderImpl sender = new MailConfig(parameterReader).javaMailSender(
                true,
                "smtp.gmail.com",
                587,
                "local@example.com",
                "local-only-password",
                PARAMETER_NAME,
                true,
                true,
                10000,
                10000,
                10000
        );

        assertEquals("local-only-password", sender.getPassword());
        verify(parameterReader, never()).read(PARAMETER_NAME);
    }
}
