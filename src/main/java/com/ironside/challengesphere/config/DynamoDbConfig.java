package com.ironside.challengesphere.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.EphemeralTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.UlidGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DynamoDbConfig.DynamoDbProperties.class)
@ConditionalOnProperty(prefix = "app.dynamodb", name = "enabled", havingValue = "true")
public class DynamoDbConfig {

    @Bean
    DynamoDbClient dynamoDbClient(
            DynamoDbProperties properties,
            Environment environment,
            @Qualifier("dynamoDbCredentialsProvider") AwsCredentialsProvider credentialsProvider
    ) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch("prod"::equalsIgnoreCase);
        properties.validate(production);
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentialsProvider);
        if (properties.getEndpoint() != null) {
            builder.endpointOverride(properties.getEndpoint());
        }
        return builder.build();
    }

    @Bean("dynamoDbCredentialsProvider")
    AwsCredentialsProvider dynamoDbCredentialsProvider(DynamoDbProperties properties) {
        return credentialsProvider(properties);
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean
    TableSchema<DynamoDbItem> dynamoDbItemTableSchema() {
        return TableSchema.fromBean(DynamoDbItem.class);
    }

    @Bean
    MainTableGateway mainTableGateway(
            DynamoDbClient dynamoDbClient,
            TableSchema<DynamoDbItem> dynamoDbItemTableSchema,
            DynamoDbProperties properties
    ) {
        return new MainTableGateway(
                dynamoDbClient,
                dynamoDbItemTableSchema,
                properties.getMainTable()
        );
    }

    @Bean
    EphemeralTableGateway ephemeralTableGateway(
            DynamoDbClient dynamoDbClient,
            TableSchema<DynamoDbItem> dynamoDbItemTableSchema,
            DynamoDbProperties properties
    ) {
        return new EphemeralTableGateway(
                dynamoDbClient,
                dynamoDbItemTableSchema,
                properties.getEphemeralTable()
        );
    }

    @Bean
    Clock dynamoDbClock() {
        return Clock.systemUTC();
    }

    @Bean
    UlidGenerator ulidGenerator(Clock dynamoDbClock) {
        return new UlidGenerator(dynamoDbClock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.dynamodb", name = "cursor-secret")
    DynamoDbCursorCodec dynamoDbCursorCodec(
            ObjectMapper objectMapper,
            DynamoDbProperties properties
    ) {
        return new DynamoDbCursorCodec(
                objectMapper,
                properties.getCursorSecret(),
                properties.getCursorMaxAge()
        );
    }

    private static AwsCredentialsProvider credentialsProvider(DynamoDbProperties properties) {
        boolean hasAccessKey = properties.getAccessKeyId() != null
                && !properties.getAccessKeyId().isBlank();
        boolean hasSecretKey = properties.getSecretAccessKey() != null
                && !properties.getSecretAccessKey().isBlank();
        if (hasAccessKey != hasSecretKey) {
            throw new IllegalArgumentException(
                    "app.dynamodb.access-key-id and secret-access-key must be configured together"
            );
        }
        if (hasAccessKey) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    properties.getAccessKeyId(),
                    properties.getSecretAccessKey()
            ));
        }
        return AwsCredentialsProviderFactory.create(properties.getProfile());
    }

    @ConfigurationProperties(prefix = "app.dynamodb")
    public static class DynamoDbProperties {

        private boolean enabled;
        private String region = "ap-south-1";
        private URI endpoint;
        private String profile;
        private String accessKeyId;
        private String secretAccessKey;
        private String mainTable = "challengesphere-main";
        private String ephemeralTable = "challengesphere-ephemeral";
        private String cursorSecret;
        private Duration cursorMaxAge = Duration.ofHours(24);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public URI getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(URI endpoint) {
            this.endpoint = endpoint;
        }

        public String getProfile() {
            return profile;
        }

        public void setProfile(String profile) {
            this.profile = profile;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getSecretAccessKey() {
            return secretAccessKey;
        }

        public void setSecretAccessKey(String secretAccessKey) {
            this.secretAccessKey = secretAccessKey;
        }

        public String getMainTable() {
            return mainTable;
        }

        public void setMainTable(String mainTable) {
            this.mainTable = mainTable;
        }

        public String getEphemeralTable() {
            return ephemeralTable;
        }

        public void setEphemeralTable(String ephemeralTable) {
            this.ephemeralTable = ephemeralTable;
        }

        public String getCursorSecret() {
            return cursorSecret;
        }

        public void setCursorSecret(String cursorSecret) {
            this.cursorSecret = cursorSecret;
        }

        public Duration getCursorMaxAge() {
            return cursorMaxAge;
        }

        public void setCursorMaxAge(Duration cursorMaxAge) {
            this.cursorMaxAge = cursorMaxAge;
        }

        private void validate(boolean production) {
            if (region == null || region.isBlank()) {
                throw new IllegalArgumentException("app.dynamodb.region must not be blank");
            }
            if (mainTable == null || mainTable.isBlank()) {
                throw new IllegalArgumentException("app.dynamodb.main-table must not be blank");
            }
            if (ephemeralTable == null || ephemeralTable.isBlank()) {
                throw new IllegalArgumentException("app.dynamodb.ephemeral-table must not be blank");
            }
            if (mainTable.equals(ephemeralTable)) {
                throw new IllegalArgumentException("main and ephemeral DynamoDB tables must be different");
            }
            if (cursorMaxAge == null || cursorMaxAge.isZero() || cursorMaxAge.isNegative()) {
                throw new IllegalArgumentException("app.dynamodb.cursor-max-age must be positive");
            }
            if (production && endpoint != null) {
                throw new IllegalArgumentException(
                        "app.dynamodb.endpoint must not be configured with the prod profile"
                );
            }
            if (production && !"ap-south-1".equalsIgnoreCase(region)) {
                throw new IllegalArgumentException(
                        "The prod DynamoDB region must be ap-south-1, but was " + region
                );
            }
        }
    }
}
