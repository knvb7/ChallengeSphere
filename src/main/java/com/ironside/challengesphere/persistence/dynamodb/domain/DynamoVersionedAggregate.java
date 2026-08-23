package com.ironside.challengesphere.persistence.dynamodb.domain;

/** Carries the canonical DynamoDB version with a detached aggregate payload. */
public interface DynamoVersionedAggregate {

    Long getDynamoVersion();

    void setDynamoVersion(Long dynamoVersion);
}
