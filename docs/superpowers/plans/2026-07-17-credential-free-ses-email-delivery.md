# Credential-Free SES Email Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace disabled Gmail SMTP delivery with Amazon SES v2 delivery authenticated exclusively by the production EC2 instance role.

**Architecture:** Preserve the public `EmailService` API and move transport behind an `EmailDeliveryGateway`. `SesEmailDeliveryGateway` builds SES simple-content requests, while Spring configuration supplies a reusable `SesV2Client` through the existing AWS credentials provider factory and validated non-secret mail properties.

**Tech Stack:** Java 17, Spring Boot 3.5.16, AWS SDK for Java 2.46.18 (`sesv2`), JUnit 5, Mockito, Amazon SES v2, EC2 IAM, Route 53, systemd, AWS Systems Manager.

## Global Constraints

- Store no mailbox password, SMTP password, long-lived AWS access key, or SES SMTP credential.
- Send transactional mail from `noreply@challengesphere.com` in `ap-south-1`.
- Authenticate production SES calls with temporary credentials from `ChallengeSphereBackendSSMRole`.
- Restrict the runtime role to `ses:SendEmail` for the `challengesphere.com` identity and exact From address.
- Keep the existing `EmailService` interface and caller behavior unchanged.
- Keep email disabled by default for local development and fail production startup on invalid enabled configuration.
- Do not add automatic retries, email attachments, inbound mail, bulk marketing, or notification-preference changes.
- Never log message bodies, password-reset codes, credentials, or full recipient addresses.
- Preserve all unrelated uncommitted workspace changes; stage and commit only files named by each task.

## File Structure

- `pom.xml` — add the SES v2 SDK transport and remove SMTP-only dependencies after the refactor.
- `src/main/java/com/ironside/challengesphere/service/EmailDeliveryGateway.java` — transport-neutral delivery contract and immutable request.
- `src/main/java/com/ironside/challengesphere/service/EmailDeliveryException.java` — transport-neutral runtime failure.
- `src/main/java/com/ironside/challengesphere/service/impl/SesEmailDeliveryGateway.java` — SES request mapping and SDK call.
- `src/main/java/com/ironside/challengesphere/config/MailDeliveryProperties.java` — non-secret mail settings and fail-fast validation.
- `src/main/java/com/ironside/challengesphere/config/SesEmailConfig.java` — reusable SES client and gateway beans.
- `src/main/java/com/ironside/challengesphere/service/impl/EmailServiceImpl.java` — existing templates and required/optional failure semantics, delegated to the gateway.
- `src/test/java/com/ironside/challengesphere/service/impl/SesEmailDeliveryGatewayTest.java` — SES request and failure mapping tests.
- `src/test/java/com/ironside/challengesphere/config/MailDeliveryPropertiesTest.java` — enabled-configuration validation tests.
- `src/test/java/com/ironside/challengesphere/service/impl/EmailServiceImplTest.java` — service behavior tests independent of AWS.
- `src/test/java/com/ironside/challengesphere/EmailCredentialFreeContractTest.java` — regression guard against SMTP and static credentials.
- `src/main/resources/application.properties` — ignored local deployment input; replace SMTP keys with non-secret SES keys.
- `src/main/resources/application-local.properties` — ignored local profile input; SES keys with mail disabled by default.
- `src/main/resources/application-prod.properties` — ignored production profile input; non-secret SES keys only.

---

### Task 1: Add the SES v2 SDK Dependency

**Files:**
- Modify: `pom.xml`
- Test: `src/test/java/com/ironside/challengesphere/SesDependencyContractTest.java`

**Interfaces:**
- Consumes: Maven project model.
- Produces: AWS SDK `software.amazon.awssdk:sesv2:2.46.18` on the compile classpath.

- [ ] **Step 1: Write the failing dependency contract test**

```java
package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SesDependencyContractTest {

    @Test
    void pomProvidesAwsSesV2SdkAtTheExistingAwsSdkVersion() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<artifactId>sesv2</artifactId>"));
        assertTrue(pom.matches("(?s).*<artifactId>sesv2</artifactId>\\s*<version>2\\.46\\.18</version>.*"));
    }
}
```

- [ ] **Step 2: Run the test and verify the red state**

Run:

```bash
./mvnw -Dtest=SesDependencyContractTest test
```

Expected: FAIL because `pom.xml` does not contain the `sesv2` artifact.

- [ ] **Step 3: Add the SES dependency beside the other AWS SDK modules**

Add this dependency immediately after the existing `ssm` dependency:

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sesv2</artifactId>
    <version>2.46.18</version>
</dependency>
```

- [ ] **Step 4: Run the focused test and compile**

Run:

```bash
./mvnw -Dtest=SesDependencyContractTest test
```

Expected: PASS with one test and zero failures.

- [ ] **Step 5: Commit only the dependency task**

```bash
git add pom.xml src/test/java/com/ironside/challengesphere/SesDependencyContractTest.java
git commit -m "build: add Amazon SES v2 SDK"
```

---

### Task 2: Implement the SES Delivery Gateway

**Files:**
- Create: `src/main/java/com/ironside/challengesphere/service/EmailDeliveryGateway.java`
- Create: `src/main/java/com/ironside/challengesphere/service/EmailDeliveryException.java`
- Create: `src/main/java/com/ironside/challengesphere/service/impl/SesEmailDeliveryGateway.java`
- Test: `src/test/java/com/ironside/challengesphere/service/impl/SesEmailDeliveryGatewayTest.java`

**Interfaces:**
- Consumes: `SesV2Client.sendEmail(SendEmailRequest)` and a configured From address.
- Produces: `String EmailDeliveryGateway.send(DeliveryRequest request)` returning the SES message ID or throwing `EmailDeliveryException`.

- [ ] **Step 1: Write the failing gateway tests**

```java
package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.service.EmailDeliveryException;
import com.ironside.challengesphere.service.EmailDeliveryGateway.DeliveryRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesEmailDeliveryGatewayTest {

    @Test
    void sendsUtf8TextAndHtmlFromConfiguredIdentity() {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-message-id").build());
        SesEmailDeliveryGateway gateway = new SesEmailDeliveryGateway(
                client, "noreply@challengesphere.com");

        String messageId = gateway.send(new DeliveryRequest(
                "member@example.com",
                "ChallengeSphere update ✓",
                "Plain body",
                "<strong>HTML body</strong>"));

        ArgumentCaptor<SendEmailRequest> requestCaptor =
                ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(requestCaptor.capture());
        SendEmailRequest request = requestCaptor.getValue();

        assertEquals("ses-message-id", messageId);
        assertEquals("noreply@challengesphere.com", request.fromEmailAddress());
        assertEquals("member@example.com", request.destination().toAddresses().get(0));
        assertEquals("ChallengeSphere update ✓", request.content().simple().subject().data());
        assertEquals("UTF-8", request.content().simple().subject().charset());
        assertEquals("Plain body", request.content().simple().body().text().data());
        assertEquals("UTF-8", request.content().simple().body().text().charset());
        assertEquals("<strong>HTML body</strong>", request.content().simple().body().html().data());
        assertEquals("UTF-8", request.content().simple().body().html().charset());
    }

    @Test
    void wrapsSdkFailuresWithoutIncludingRecipientOrBody() {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(
                SdkClientException.create("transport unavailable"));
        SesEmailDeliveryGateway gateway = new SesEmailDeliveryGateway(
                client, "noreply@challengesphere.com");

        EmailDeliveryException failure = assertThrows(
                EmailDeliveryException.class,
                () -> gateway.send(new DeliveryRequest(
                        "private@example.com", "Subject", null, "reset-code-123")));

        assertEquals("Amazon SES email delivery failed", failure.getMessage());
        assertEquals(SdkClientException.class, failure.getCause().getClass());
    }
}
```

- [ ] **Step 2: Run the gateway test and verify the red state**

Run:

```bash
./mvnw -Dtest=SesEmailDeliveryGatewayTest test
```

Expected: compilation FAIL because the gateway contract, exception, and SES implementation do not exist.

- [ ] **Step 3: Create the transport-neutral contract**

```java
package com.ironside.challengesphere.service;

public interface EmailDeliveryGateway {

    String send(DeliveryRequest request);

    record DeliveryRequest(
            String recipient,
            String subject,
            String textBody,
            String htmlBody
    ) {
    }
}
```

- [ ] **Step 4: Create the transport-neutral failure**

```java
package com.ironside.challengesphere.service;

public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: Implement SES request mapping and delivery**

```java
package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.service.EmailDeliveryException;
import com.ironside.challengesphere.service.EmailDeliveryGateway;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

public class SesEmailDeliveryGateway implements EmailDeliveryGateway {

    private static final String UTF_8 = "UTF-8";

    private final SesV2Client sesClient;
    private final String fromAddress;

    public SesEmailDeliveryGateway(SesV2Client sesClient, String fromAddress) {
        this.sesClient = sesClient;
        this.fromAddress = fromAddress;
    }

    @Override
    public String send(DeliveryRequest request) {
        Body.Builder body = Body.builder();
        if (request.textBody() != null && !request.textBody().isBlank()) {
            body.text(content(request.textBody()));
        }
        if (request.htmlBody() != null && !request.htmlBody().isBlank()) {
            body.html(content(request.htmlBody()));
        }

        SendEmailRequest sesRequest = SendEmailRequest.builder()
                .fromEmailAddress(fromAddress)
                .destination(Destination.builder()
                        .toAddresses(request.recipient())
                        .build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(content(request.subject()))
                                .body(body.build())
                                .build())
                        .build())
                .build();
        try {
            return sesClient.sendEmail(sesRequest).messageId();
        } catch (SdkException failure) {
            throw new EmailDeliveryException("Amazon SES email delivery failed", failure);
        }
    }

    private static Content content(String data) {
        return Content.builder().data(data).charset(UTF_8).build();
    }
}
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
./mvnw -Dtest=SesDependencyContractTest,SesEmailDeliveryGatewayTest test
```

Expected: PASS with three tests and zero failures.

- [ ] **Step 7: Commit the gateway**

```bash
git add \
  src/main/java/com/ironside/challengesphere/service/EmailDeliveryGateway.java \
  src/main/java/com/ironside/challengesphere/service/EmailDeliveryException.java \
  src/main/java/com/ironside/challengesphere/service/impl/SesEmailDeliveryGateway.java \
  src/test/java/com/ironside/challengesphere/service/impl/SesEmailDeliveryGatewayTest.java
git commit -m "feat: add Amazon SES email gateway"
```

---

### Task 3: Validate Credential-Free Mail Configuration

**Files:**
- Create: `src/main/java/com/ironside/challengesphere/config/MailDeliveryProperties.java`
- Create: `src/main/java/com/ironside/challengesphere/config/SesEmailConfig.java`
- Test: `src/test/java/com/ironside/challengesphere/config/MailDeliveryPropertiesTest.java`

**Interfaces:**
- Consumes: `app.mail.enabled`, `app.mail.provider`, `app.mail.from-address`, `app.mail.ses-region`, and optional `aws.credentials.profile`.
- Produces: validated `MailDeliveryProperties`, a reusable `SesV2Client`, and an `EmailDeliveryGateway` Spring bean.

- [ ] **Step 1: Write failing validation tests**

```java
package com.ironside.challengesphere.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MailDeliveryPropertiesTest {

    @Test
    void disabledLocalConfigurationNeedsNoExternalSetup() {
        MailDeliveryProperties properties = new MailDeliveryProperties();
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void acceptsEnabledSesConfiguration() {
        MailDeliveryProperties properties = enabledProperties();
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void rejectsUnsupportedProviderWhenEnabled() {
        MailDeliveryProperties properties = enabledProperties();
        properties.setProvider("smtp");
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsMissingFromAddressWhenEnabled() {
        MailDeliveryProperties properties = enabledProperties();
        properties.setFromAddress(" ");
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsInvalidRegionWhenEnabled() {
        MailDeliveryProperties properties = enabledProperties();
        properties.setSesRegion("not a region");
        assertThrows(IllegalStateException.class, properties::validate);
    }

    private static MailDeliveryProperties enabledProperties() {
        MailDeliveryProperties properties = new MailDeliveryProperties();
        properties.setEnabled(true);
        properties.setProvider("ses");
        properties.setFromAddress("noreply@challengesphere.com");
        properties.setSesRegion("ap-south-1");
        return properties;
    }
}
```

- [ ] **Step 2: Run the test and verify the red state**

Run:

```bash
./mvnw -Dtest=MailDeliveryPropertiesTest test
```

Expected: compilation FAIL because `MailDeliveryProperties` does not exist.

- [ ] **Step 3: Implement validated non-secret properties**

```java
package com.ironside.challengesphere.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mail")
public class MailDeliveryProperties {

    private boolean enabled;
    private String provider = "ses";
    private String fromAddress = "noreply@challengesphere.com";
    private String sesRegion = "ap-south-1";

    @PostConstruct
    void validate() {
        if (!enabled) {
            return;
        }
        if (!"ses".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("app.mail.provider must be ses when mail is enabled");
        }
        if (!StringUtils.hasText(fromAddress)) {
            throw new IllegalStateException("app.mail.from-address is required when mail is enabled");
        }
        if (!"ap-south-1".equals(sesRegion)) {
            throw new IllegalStateException("app.mail.ses-region must be ap-south-1");
        }
    }
}
```

- [ ] **Step 4: Create SES Spring beans with the existing credentials factory**

```java
package com.ironside.challengesphere.config;

import com.ironside.challengesphere.service.EmailDeliveryGateway;
import com.ironside.challengesphere.service.impl.SesEmailDeliveryGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration
public class SesEmailConfig {

    @Bean(destroyMethod = "close")
    SesV2Client sesV2Client(
            MailDeliveryProperties properties,
            @Value("${aws.credentials.profile:}") String awsProfile
    ) {
        return SesV2Client.builder()
                .region(Region.of(properties.getSesRegion()))
                .credentialsProvider(AwsCredentialsProviderFactory.create(awsProfile))
                .build();
    }

    @Bean
    EmailDeliveryGateway emailDeliveryGateway(
            SesV2Client sesClient,
            MailDeliveryProperties properties
    ) {
        return new SesEmailDeliveryGateway(sesClient, properties.getFromAddress());
    }
}
```

- [ ] **Step 5: Run focused configuration and gateway tests**

Run:

```bash
./mvnw -Dtest=MailDeliveryPropertiesTest,SesEmailDeliveryGatewayTest test
```

Expected: PASS with seven tests and zero failures.

- [ ] **Step 6: Commit configuration**

```bash
git add \
  src/main/java/com/ironside/challengesphere/config/MailDeliveryProperties.java \
  src/main/java/com/ironside/challengesphere/config/SesEmailConfig.java \
  src/test/java/com/ironside/challengesphere/config/MailDeliveryPropertiesTest.java
git commit -m "feat: configure credential-free SES delivery"
```

---

### Task 4: Refactor EmailService onto the Gateway and Remove SMTP

**Files:**
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/EmailServiceImpl.java`
- Modify: `pom.xml`
- Test: `src/test/java/com/ironside/challengesphere/service/impl/EmailServiceImplTest.java`
- Test: `src/test/java/com/ironside/challengesphere/EmailCredentialFreeContractTest.java`

**Interfaces:**
- Consumes: `EmailDeliveryGateway.send(DeliveryRequest)` and `MailDeliveryProperties.isEnabled()`.
- Produces: unchanged `EmailService` behavior using SES rather than `JavaMailSender`.

- [ ] **Step 1: Write failing service behavior tests**

```java
package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.config.MailDeliveryProperties;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.service.EmailDeliveryException;
import com.ironside.challengesphere.service.EmailDeliveryGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EmailServiceImplTest {

    @Test
    void requiredHtmlDeliveryUsesGateway() throws Exception {
        EmailDeliveryGateway gateway = mock(EmailDeliveryGateway.class);
        EmailServiceImpl service = service(gateway, true);

        service.sendHtmlEmail("member@example.com", "Support", "<p>Help</p>");

        ArgumentCaptor<EmailDeliveryGateway.DeliveryRequest> captor =
                ArgumentCaptor.forClass(EmailDeliveryGateway.DeliveryRequest.class);
        verify(gateway).send(captor.capture());
        assertEquals("member@example.com", captor.getValue().recipient());
        assertEquals("Support", captor.getValue().subject());
        assertEquals("<p>Help</p>", captor.getValue().htmlBody());
    }

    @Test
    void requiredDeliveryReportsServiceUnavailableWhenDisabled() {
        EmailDeliveryGateway gateway = mock(EmailDeliveryGateway.class);
        EmailServiceImpl service = service(gateway, false);

        ChallengeSphereException failure = assertThrows(
                ChallengeSphereException.class,
                () -> service.sendHtmlEmail("member@example.com", "Support", "<p>Help</p>"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        verify(gateway, never()).send(any());
    }

    @Test
    void requiredDeliveryMapsGatewayFailureToBadGateway() {
        EmailDeliveryGateway gateway = mock(EmailDeliveryGateway.class);
        doThrow(new EmailDeliveryException("Amazon SES email delivery failed",
                new IllegalStateException("transport")))
                .when(gateway).send(any());
        EmailServiceImpl service = service(gateway, true);

        ChallengeSphereException failure = assertThrows(
                ChallengeSphereException.class,
                () -> service.sendHtmlEmail("member@example.com", "Support", "<p>Help</p>"));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatus());
    }

    @Test
    void optionalPlainDeliveryContainsGatewayFailure() {
        EmailDeliveryGateway gateway = mock(EmailDeliveryGateway.class);
        doThrow(new EmailDeliveryException("Amazon SES email delivery failed",
                new IllegalStateException("transport")))
                .when(gateway).send(any());
        EmailServiceImpl service = service(gateway, true);

        service.sendPlainEmail("member@example.com", "Subject", "Body");

        verify(gateway).send(any());
    }

    @Test
    void welcomeTemplateStillEscapesUsername() {
        EmailDeliveryGateway gateway = mock(EmailDeliveryGateway.class);
        EmailServiceImpl service = service(gateway, true);
        com.ironside.challengesphere.model.User user = new com.ironside.challengesphere.model.User();
        user.setEmailId("member@example.com");
        user.setUsername("<Admin>");

        service.sendWelcomeEmail(user);

        ArgumentCaptor<EmailDeliveryGateway.DeliveryRequest> captor =
                ArgumentCaptor.forClass(EmailDeliveryGateway.DeliveryRequest.class);
        verify(gateway).send(captor.capture());
        assertTrue(captor.getValue().htmlBody().contains("&lt;Admin&gt;"));
    }

    private static EmailServiceImpl service(EmailDeliveryGateway gateway, boolean enabled) {
        MailDeliveryProperties properties = new MailDeliveryProperties();
        properties.setEnabled(enabled);
        EmailServiceImpl service = new EmailServiceImpl(gateway, properties);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://challengesphere.com");
        return service;
    }
}
```

- [ ] **Step 2: Run the service test and verify the red state**

Run:

```bash
./mvnw -Dtest=EmailServiceImplTest test
```

Expected: compilation FAIL because `EmailServiceImpl` still requires `JavaMailSender`.

- [ ] **Step 3: Replace SMTP fields and constructor in EmailServiceImpl**

Remove all `jakarta.mail` and `org.springframework.mail` imports. Add:

```java
import com.ironside.challengesphere.config.MailDeliveryProperties;
import com.ironside.challengesphere.service.EmailDeliveryException;
import com.ironside.challengesphere.service.EmailDeliveryGateway;
import com.ironside.challengesphere.service.EmailDeliveryGateway.DeliveryRequest;
```

Replace the mail fields and constructor with:

```java
private final EmailDeliveryGateway deliveryGateway;
private final MailDeliveryProperties mailProperties;

@Value("${app.frontend.base-url:https://challengesphere.com}")
private String frontendBaseUrl;

public EmailServiceImpl(
        EmailDeliveryGateway deliveryGateway,
        MailDeliveryProperties mailProperties
) {
    this.deliveryGateway = deliveryGateway;
    this.mailProperties = mailProperties;
}
```

- [ ] **Step 4: Replace the three delivery helpers**

Replace `sendPlainEmail`, `sendHtml`, and `sendHtmlOrThrow` with:

```java
@Override
@Async("cacheExecutor")
public void sendPlainEmail(String to, String subject, String body) {
    if (!mailProperties.isEnabled()) {
        logger.info("Mail disabled, skipping plain email");
        return;
    }
    try {
        String messageId = deliveryGateway.send(new DeliveryRequest(to, subject, body, null));
        logger.info("Sent plain email through SES | messageId={}", messageId);
    } catch (EmailDeliveryException failure) {
        logger.error("Failed to send plain email | exception={}",
                failure.getCause() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getCause().getClass().getSimpleName());
    }
}

private void sendHtml(String to, String subject, String html) {
    if (!mailProperties.isEnabled()) {
        logger.info("Mail disabled, skipping HTML email");
        return;
    }
    try {
        String messageId = deliveryGateway.send(new DeliveryRequest(to, subject, null, html));
        logger.info("Sent HTML email through SES | messageId={}", messageId);
    } catch (EmailDeliveryException failure) {
        logger.error("Failed to send HTML email | exception={}",
                failure.getCause() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getCause().getClass().getSimpleName());
    }
}

private void sendHtmlOrThrow(String to, String subject, String html)
        throws ChallengeSphereException {
    if (!mailProperties.isEnabled()) {
        logger.warn("Mail disabled, cannot send required HTML email");
        throw new ChallengeSphereException(
                "Email delivery is not configured",
                HttpStatus.SERVICE_UNAVAILABLE,
                logger);
    }
    try {
        String messageId = deliveryGateway.send(new DeliveryRequest(to, subject, null, html));
        logger.info("Sent required HTML email through SES | messageId={}", messageId);
    } catch (EmailDeliveryException failure) {
        logger.error("Failed to send required HTML email | exception={}",
                failure.getCause() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getCause().getClass().getSimpleName());
        throw new ChallengeSphereException(
                "Failed to send email",
                HttpStatus.BAD_GATEWAY,
                logger,
                failure);
    }
}
```

- [ ] **Step 5: Run service and gateway tests**

Run:

```bash
./mvnw -Dtest=EmailServiceImplTest,SesEmailDeliveryGatewayTest test
```

Expected: PASS with seven tests and zero failures.

- [ ] **Step 6: Write the credential-free regression contract**

```java
package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailCredentialFreeContractTest {

    @Test
    void productionCodeUsesSesApiWithoutSmtpOrStaticCredentials() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<artifactId>sesv2</artifactId>"));
        assertFalse(pom.contains("<artifactId>spring-boot-starter-mail</artifactId>"));
        assertFalse(pom.contains("<artifactId>jakarta.mail-api</artifactId>"));

        List<String> forbidden = List.of(
                "JavaMailSender",
                "MimeMessageHelper",
                "SimpleMailMessage",
                "StaticCredentialsProvider");
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source);
                for (String token : forbidden) {
                    assertFalse(content.contains(token),
                            () -> source + " contains forbidden email credential/SMTP token " + token);
                }
            }
        }
    }
}
```

- [ ] **Step 7: Run the contract and verify the red state**

Run:

```bash
./mvnw -Dtest=EmailCredentialFreeContractTest test
```

Expected: FAIL because `pom.xml` still contains the Spring Mail and Jakarta Mail dependencies.

- [ ] **Step 8: Remove SMTP-only dependencies**

Delete these complete dependency blocks from `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-logging</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>jakarta.mail</groupId>
    <artifactId>jakarta.mail-api</artifactId>
    <version>2.1.3</version>
</dependency>
```

- [ ] **Step 9: Run all email-focused tests**

Run:

```bash
./mvnw -Dtest=SesDependencyContractTest,EmailCredentialFreeContractTest,MailDeliveryPropertiesTest,SesEmailDeliveryGatewayTest,EmailServiceImplTest test
```

Expected: PASS with fourteen tests and zero failures.

- [ ] **Step 10: Commit service refactor and SMTP removal**

```bash
git add \
  pom.xml \
  src/main/java/com/ironside/challengesphere/service/impl/EmailServiceImpl.java \
  src/test/java/com/ironside/challengesphere/service/impl/EmailServiceImplTest.java \
  src/test/java/com/ironside/challengesphere/EmailCredentialFreeContractTest.java
git commit -m "feat: deliver backend email through SES"
```

---

### Task 5: Replace Local Deployment SMTP Settings with SES Settings

**Files:**
- Modify (ignored deployment input): `src/main/resources/application.properties`
- Modify (ignored deployment input): `src/main/resources/application-local.properties`
- Modify (ignored deployment input): `src/main/resources/application-prod.properties`

**Interfaces:**
- Consumes: Spring relaxed binding from `APP_MAIL_*` environment variables.
- Produces: packaged profile defaults containing no SMTP user or password reference.

- [ ] **Step 1: Record the current SMTP-key red state without printing values**

Run:

```bash
rg -l '^(spring\.mail\.|.*MAIL_(USERNAME|PASSWORD))' src/main/resources/application*.properties
```

Expected: all three application property files are listed.

- [ ] **Step 2: Replace each SMTP block with the profile-appropriate SES block**

Use this block in `application.properties` and `application-local.properties`:

```properties
# Credential-free transactional email through the AWS SDK and EC2 instance role.
app.mail.enabled=${APP_MAIL_ENABLED:false}
app.mail.provider=${APP_MAIL_PROVIDER:ses}
app.mail.from-address=${APP_MAIL_FROM_ADDRESS:noreply@challengesphere.com}
app.mail.ses-region=${APP_MAIL_SES_REGION:ap-south-1}
```

Use this fail-closed block in `application-prod.properties`; systemd explicitly enables it only after SES verification:

```properties
# Credential-free transactional email through the AWS SDK and EC2 instance role.
app.mail.enabled=${APP_MAIL_ENABLED:false}
app.mail.provider=${APP_MAIL_PROVIDER:ses}
app.mail.from-address=${APP_MAIL_FROM_ADDRESS:noreply@challengesphere.com}
app.mail.ses-region=${APP_MAIL_SES_REGION:ap-south-1}
```

Delete `spring.mail.host`, `spring.mail.port`, `spring.mail.username`,
`spring.mail.password`, and every `spring.mail.properties.mail.smtp.*` key.

- [ ] **Step 3: Verify no SMTP credential configuration remains**

Run:

```bash
if rg -n '^(spring\.mail\.|.*MAIL_(USERNAME|PASSWORD))' src/main/resources/application*.properties; then
  exit 1
fi
```

Expected: exit 0 with no output.

- [ ] **Step 4: Build the package and inspect its configuration safely**

Run:

```bash
./mvnw -DskipTests package
for profile in application.properties application-local.properties application-prod.properties; do
  unzip -p target/challengesphere-0.0.1-SNAPSHOT.jar "BOOT-INF/classes/$profile" \
    | rg '^(app\.mail\.|spring\.mail\.)'
done
```

Expected: each profile shows only the four non-secret `app.mail.*` SES keys and no `spring.mail.*` key.

These files are intentionally ignored by the repository, so do not force-add or commit them.

---

### Task 6: Configure SES Identity, DKIM, Production Access, and Least-Privilege IAM

**Files:**
- No repository files.
- AWS resources: SES identity `challengesphere.com`, Route 53 hosted zone `Z0798153206RHUBQAX51J`, IAM role `ChallengeSphereBackendSSMRole`.

**Interfaces:**
- Consumes: authorized operator AWS session for account `198300191358`.
- Produces: verified SES domain and EC2 permission to send only as `noreply@challengesphere.com`.

- [ ] **Step 1: Reconfirm the AWS account and pre-change SES state**

Run:

```bash
aws sts get-caller-identity --query '{Account:Account,Arn:Arn}' --output json
aws sesv2 get-account --region ap-south-1 \
  --query '{ProductionAccessEnabled:ProductionAccessEnabled,SendingEnabled:SendingEnabled}' \
  --output json
aws sesv2 get-email-identity --region ap-south-1 \
  --email-identity challengesphere.com \
  --query '{Verified:VerifiedForSendingStatus,Dkim:DkimAttributes.Status}' \
  --output json
```

Expected: account `198300191358`; production access false; the identity lookup initially returns NotFound.

- [ ] **Step 2: Create the SES domain identity if absent**

Run:

```bash
aws sesv2 create-email-identity \
  --region ap-south-1 \
  --email-identity challengesphere.com \
  --query '{IdentityType:IdentityType,DkimTokens:DkimAttributes.Tokens}' \
  --output json
```

Expected: identity type `DOMAIN` and three DKIM tokens.

- [ ] **Step 3: Publish all DKIM records in Route 53**

Run:

```bash
tokens=$(aws sesv2 get-email-identity \
  --region ap-south-1 \
  --email-identity challengesphere.com \
  --query 'DkimAttributes.Tokens' \
  --output json)
changes=$(jq -n --argjson tokens "$tokens" '{
  Comment:"Verify challengesphere.com for Amazon SES",
  Changes:[$tokens[] | {
    Action:"UPSERT",
    ResourceRecordSet:{
      Name:(. + "._domainkey.challengesphere.com"),
      Type:"CNAME",
      TTL:300,
      ResourceRecords:[{Value:(. + ".dkim.amazonses.com")}]
    }
  }]
}')
aws route53 change-resource-record-sets \
  --hosted-zone-id Z0798153206RHUBQAX51J \
  --change-batch "$changes" \
  --query 'ChangeInfo.{Id:Id,Status:Status}' \
  --output json
```

Expected: Route 53 returns a change ID with status `PENDING`.

- [ ] **Step 4: Wait for SES domain verification**

Run every 30 seconds, without exceeding 15 minutes:

```bash
aws sesv2 get-email-identity \
  --region ap-south-1 \
  --email-identity challengesphere.com \
  --query '{Verified:VerifiedForSendingStatus,Dkim:DkimAttributes.Status}' \
  --output json
```

Expected: `Verified=true` and `Dkim=SUCCESS`. If verification remains pending after 15 minutes, leave email disabled and report the pending external state.

- [ ] **Step 5: Attach the least-privilege SES sending policy**

Run:

```bash
policy=$(jq -n '{
  Version:"2012-10-17",
  Statement:[{
    Sid:"SendChallengeSphereTransactionalEmail",
    Effect:"Allow",
    Action:"ses:SendEmail",
    Resource:"arn:aws:ses:ap-south-1:198300191358:identity/challengesphere.com",
    Condition:{StringEquals:{"ses:FromAddress":"noreply@challengesphere.com"}}
  }]
}')
aws iam put-role-policy \
  --role-name ChallengeSphereBackendSSMRole \
  --policy-name ChallengeSphereSesSend \
  --policy-document "$policy"
aws iam get-role-policy \
  --role-name ChallengeSphereBackendSSMRole \
  --policy-name ChallengeSphereSesSend \
  --query PolicyDocument \
  --output json
```

Expected: the read-back policy contains only `ses:SendEmail`, the domain identity ARN, and the exact From-address condition.

- [ ] **Step 6: Request production access if still sandboxed**

Run:

```bash
aws sesv2 put-account-details \
  --region ap-south-1 \
  --production-access-enabled \
  --mail-type TRANSACTIONAL \
  --website-url https://challengesphere.com \
  --additional-contact-email-addresses ironside-dev@challengesphere.com \
  --contact-language EN \
  --use-case-description 'ChallengeSphere sends user-requested password reset codes, account welcome messages, notification digests, and authenticated support ticket notifications. We send only transactional email to registered users, honor notification preferences, do not use purchased lists, and monitor SES bounces and complaints.'
aws sesv2 get-account --region ap-south-1 \
  --query '{ProductionAccessEnabled:ProductionAccessEnabled,EnforcementStatus:EnforcementStatus}' \
  --output json
```

Expected: the request is accepted. `ProductionAccessEnabled` may remain false while AWS reviews it; sandbox status does not block mailbox-simulator smoke testing.

---

### Task 7: Verify, Deploy, and Smoke-Test Production

**Files:**
- Built artifact: `target/challengesphere-0.0.1-SNAPSHOT.jar`
- Remote artifact: `/opt/challengesphere/challengesphere.jar`
- Remote environment: `/etc/challengesphere/challengesphere.env`

**Interfaces:**
- Consumes: verified SES identity, IAM policy, packaged application, SSM-online EC2 instance `i-02ed0080ff5c29077`.
- Produces: healthy production backend with SES enabled and no email credential in its environment.

- [ ] **Step 1: Run fresh local verification**

Run:

```bash
./mvnw -Dtest=SesDependencyContractTest,EmailCredentialFreeContractTest,MailDeliveryPropertiesTest,SesEmailDeliveryGatewayTest,EmailServiceImplTest test
./mvnw test
./mvnw -DskipTests package
git diff --check
```

Expected: every command exits 0; all tests report zero failures; the package exists at `target/challengesphere-0.0.1-SNAPSHOT.jar`.

- [ ] **Step 2: Upload the exact verified artifact to the existing private deployment path**

Run:

```bash
artifact_sha=$(shasum -a 256 target/challengesphere-0.0.1-SNAPSHOT.jar | awk '{print $1}')
deploy_key="deployments/email-ses/challengesphere-${artifact_sha}.jar"
aws s3 cp target/challengesphere-0.0.1-SNAPSHOT.jar \
  "s3://challengesphere-submissions-prod-ap-south-1/${deploy_key}" \
  --region ap-south-1
printf 'artifact_sha=%s\ndeploy_key=%s\n' "$artifact_sha" "$deploy_key"
```

Expected: upload succeeds and prints a SHA-256 plus immutable deployment key.

- [ ] **Step 3: Download, checksum, install, and configure through SSM**

Build an SSM command whose shell script performs these exact operations:

```bash
set -euo pipefail
aws s3 cp "s3://challengesphere-submissions-prod-ap-south-1/${deploy_key}" \
  /tmp/challengesphere-email-ses.jar --region ap-south-1
echo "${artifact_sha}  /tmp/challengesphere-email-ses.jar" | sha256sum -c -
install -m 0644 /opt/challengesphere/challengesphere.jar \
  "/opt/challengesphere/challengesphere.jar.backup-$(date +%Y%m%d%H%M%S)"
install -m 0644 /tmp/challengesphere-email-ses.jar /opt/challengesphere/challengesphere.jar
install -m 0600 /etc/challengesphere/challengesphere.env \
  "/etc/challengesphere/challengesphere.env.backup-$(date +%Y%m%d%H%M%S)"
sed -i \
  -e '/^APP_MAIL_ENABLED=/d' \
  -e '/^APP_MAIL_PROVIDER=/d' \
  -e '/^APP_MAIL_FROM_ADDRESS=/d' \
  -e '/^APP_MAIL_SES_REGION=/d' \
  -e '/^AWS_EC2_METADATA_V1_DISABLED=/d' \
  -e '/^MAIL_USERNAME=/d' \
  -e '/^MAIL_PASSWORD=/d' \
  -e '/^MAIL_SMTP_AUTH=/d' \
  /etc/challengesphere/challengesphere.env
printf '%s\n' \
  'APP_MAIL_ENABLED=true' \
  'APP_MAIL_PROVIDER=ses' \
  'APP_MAIL_FROM_ADDRESS=noreply@challengesphere.com' \
  'APP_MAIL_SES_REGION=ap-south-1' \
  'AWS_EC2_METADATA_V1_DISABLED=true' \
  >> /etc/challengesphere/challengesphere.env
systemctl daemon-reload
systemctl restart challengesphere.service
```

Invoke it with `aws ssm send-command`, wait with `aws ssm wait command-executed`, then retrieve the invocation. Expected: status `Success` and no checksum error.

- [ ] **Step 4: Verify health, runtime configuration state, and logs without exposing secrets**

Run through SSM:

```bash
systemctl is-active challengesphere.service
curl --fail --silent http://127.0.0.1:8080/actuator/health
pid=$(pgrep -f '/opt/challengesphere/challengesphere.jar' | head -1)
for key in SPRING_PROFILES_ACTIVE APP_MAIL_ENABLED APP_MAIL_PROVIDER APP_MAIL_FROM_ADDRESS APP_MAIL_SES_REGION AWS_EC2_METADATA_V1_DISABLED MAIL_USERNAME MAIL_PASSWORD; do
  value=$(tr '\0' '\n' < "/proc/$pid/environ" | sed -n "s/^${key}=//p" | head -1)
  case "$key" in
    MAIL_USERNAME|MAIL_PASSWORD)
      if [ -n "$value" ]; then echo "$key=SET"; else echo "$key=UNSET"; fi
      ;;
    *) echo "$key=${value:-<unset>}" ;;
  esac
done
journalctl -u challengesphere.service --since '-5 minutes' --no-pager \
  | grep -E 'Started|Application started|ERROR|Exception' \
  | tail -80
```

Expected: service `active`; health status `UP`; SES settings match the plan; username/password are `UNSET`; no startup exception.

- [ ] **Step 5: Smoke-test SES from the EC2 role**

Run through SSM:

```bash
aws sesv2 send-email \
  --region ap-south-1 \
  --from-email-address noreply@challengesphere.com \
  --destination '{"ToAddresses":["success@simulator.amazonses.com"]}' \
  --content '{"Simple":{"Subject":{"Data":"ChallengeSphere SES smoke test","Charset":"UTF-8"},"Body":{"Text":{"Data":"Credential-free SES delivery is operational.","Charset":"UTF-8"}}}}' \
  --query MessageId \
  --output text
```

Expected: SES returns a message ID. This proves domain verification, instance-role credentials, IAM scope, region, and SES sending independent of any stored password.

- [ ] **Step 6: Roll back a failed deployment or complete the production-mailbox smoke test**

If Step 4 or Step 5 fails, run this rollback through SSM before continuing:

```bash
set -euo pipefail
backup_jar=$(ls -1t /opt/challengesphere/challengesphere.jar.backup-* | head -1)
backup_env=$(ls -1t /etc/challengesphere/challengesphere.env.backup-* | head -1)
install -m 0644 "$backup_jar" /opt/challengesphere/challengesphere.jar
install -m 0600 "$backup_env" /etc/challengesphere/challengesphere.env
systemctl restart challengesphere.service
systemctl is-active challengesphere.service
curl --fail --silent http://127.0.0.1:8080/actuator/health
```

Expected on rollback: the previous backend is active and its health status is `UP`. Report the failed verification evidence rather than claiming the SES deployment succeeded.

If production access is already approved, also send one transactional smoke message from the EC2 role to the operational support mailbox:

```bash
aws sesv2 send-email \
  --region ap-south-1 \
  --from-email-address noreply@challengesphere.com \
  --destination '{"ToAddresses":["ironside-dev@challengesphere.com"]}' \
  --content '{"Simple":{"Subject":{"Data":"ChallengeSphere email delivery enabled","Charset":"UTF-8"},"Body":{"Text":{"Data":"Credential-free transactional email delivery is active in production.","Charset":"UTF-8"}}}}' \
  --query MessageId \
  --output text
```

Expected when production access is approved: SES returns a message ID and the message arrives at the operational mailbox.

- [ ] **Step 7: Remove the temporary deployment object and inspect final repository scope**

Run locally:

```bash
aws s3 rm "s3://challengesphere-submissions-prod-ap-south-1/${deploy_key}" \
  --region ap-south-1
git status --short
git log -5 --oneline
```

Expected: the temporary S3 object is removed. Git status still shows the user's pre-existing unrelated changes plus only the intentionally ignored application-property edits; the email implementation commits are visible at the top of the log.

- [ ] **Step 8: Report the only possible user follow-up**

If `ProductionAccessEnabled=false`, report that AWS is reviewing the SES production-access request and that the user may need to answer an AWS support email sent to `ironside-dev@challengesphere.com`. If it is true, report that no user action remains.
