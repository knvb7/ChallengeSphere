package com.ironside.challengesphere.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Configuration
public class MailConfig {

    private final SsmSecureParameterReader parameterReader;

    public MailConfig(SsmSecureParameterReader parameterReader) {
        this.parameterReader = parameterReader;
    }

    @Bean
    JavaMailSenderImpl javaMailSender(
            @Value("${app.mail.enabled:false}") boolean mailEnabled,
            @Value("${spring.mail.host:smtp.gmail.com}") String host,
            @Value("${spring.mail.port:587}") int port,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String configuredPassword,
            @Value("${spring.mail.password-parameter-name:}") String passwordParameterName,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean smtpAuth,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean startTls,
            @Value("${spring.mail.properties.mail.smtp.connectiontimeout:10000}") int connectionTimeout,
            @Value("${spring.mail.properties.mail.smtp.timeout:10000}") int readTimeout,
            @Value("${spring.mail.properties.mail.smtp.writetimeout:10000}") int writeTimeout
    ) {
        String password = resolvePassword(
                mailEnabled,
                configuredPassword,
                passwordParameterName
        );
        if (mailEnabled && !StringUtils.hasText(username)) {
            throw new IllegalStateException(
                    "spring.mail.username is required when mail is enabled"
            );
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(smtpAuth));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(startTls));
        properties.setProperty(
                "mail.smtp.connectiontimeout",
                Integer.toString(connectionTimeout)
        );
        properties.setProperty("mail.smtp.timeout", Integer.toString(readTimeout));
        properties.setProperty("mail.smtp.writetimeout", Integer.toString(writeTimeout));
        return sender;
    }

    private String resolvePassword(
            boolean mailEnabled,
            String configuredPassword,
            String passwordParameterName
    ) {
        if (!mailEnabled) {
            return "";
        }
        if (StringUtils.hasText(configuredPassword)) {
            return configuredPassword;
        }
        if (!StringUtils.hasText(passwordParameterName)) {
            throw new IllegalStateException(
                    "Email password secure parameter name is not configured"
            );
        }

        String password = parameterReader.read(passwordParameterName);
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException("Email password secure parameter is empty");
        }
        return password;
    }
}
