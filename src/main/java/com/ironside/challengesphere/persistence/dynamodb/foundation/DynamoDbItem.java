package com.ironside.challengesphere.persistence.dynamodb.foundation;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonical envelope stored in both ChallengeSphere DynamoDB tables.
 *
 * <p>Domain payloads are serialized as JSON so the envelope remains stable as
 * individual aggregates evolve. All sortable timestamps use UTC ISO-8601 text;
 * {@code expiresAt} is the numeric epoch-second attribute consumed by DynamoDB
 * TTL.</p>
 */
@DynamoDbBean
public class DynamoDbItem {

    private String pk;
    private String sk;
    private String entityType;
    private String payloadJson;
    private Long version;
    private String createdAt;
    private String updatedAt;
    private Long expiresAt;
    private String gsi1Pk;
    private String gsi1Sk;
    private String gsi2Pk;
    private String gsi2Sk;
    private String gsi3Pk;
    private String gsi3Sk;
    private String gsi4Pk;
    private String gsi4Sk;
    private String gsi5Pk;
    private String gsi5Sk;
    private String gsi6Pk;
    private String gsi6Sk;

    public DynamoDbItem() {
        // Required by the enhanced-client bean schema.
    }

    public static DynamoDbItem create(
            DynamoDbKey key,
            String entityType,
            String payloadJson,
            Instant now
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(now, "now");
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType must not be blank");
        }
        DynamoDbItem item = new DynamoDbItem();
        item.setPk(key.partitionKey());
        item.setSk(key.sortKey());
        item.setEntityType(entityType);
        item.setPayloadJson(Objects.requireNonNull(payloadJson, "payloadJson"));
        item.setVersion(1L);
        item.setCreatedAt(now.toString());
        item.setUpdatedAt(now.toString());
        return item;
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute(DynamoDbKeys.PARTITION_KEY)
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute(DynamoDbKeys.SORT_KEY)
    public String getSk() {
        return sk;
    }

    public void setSk(String sk) {
        this.sk = sk;
    }

    @DynamoDbAttribute(DynamoDbKeys.ENTITY_TYPE)
    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    @DynamoDbAttribute(DynamoDbKeys.PAYLOAD_JSON)
    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    @DynamoDbAttribute(DynamoDbKeys.VERSION)
    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    @DynamoDbAttribute(DynamoDbKeys.CREATED_AT)
    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute(DynamoDbKeys.UPDATED_AT)
    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    @DynamoDbAttribute(DynamoDbKeys.EXPIRES_AT)
    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = DynamoDbKeys.GSI1_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI1_PARTITION_KEY)
    public String getGsi1Pk() {
        return gsi1Pk;
    }

    public void setGsi1Pk(String gsi1Pk) {
        this.gsi1Pk = gsi1Pk;
    }

    @DynamoDbSecondarySortKey(indexNames = DynamoDbKeys.GSI1_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI1_SORT_KEY)
    public String getGsi1Sk() {
        return gsi1Sk;
    }

    public void setGsi1Sk(String gsi1Sk) {
        this.gsi1Sk = gsi1Sk;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = DynamoDbKeys.GSI2_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI2_PARTITION_KEY)
    public String getGsi2Pk() {
        return gsi2Pk;
    }

    public void setGsi2Pk(String gsi2Pk) {
        this.gsi2Pk = gsi2Pk;
    }

    @DynamoDbSecondarySortKey(indexNames = DynamoDbKeys.GSI2_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI2_SORT_KEY)
    public String getGsi2Sk() {
        return gsi2Sk;
    }

    public void setGsi2Sk(String gsi2Sk) {
        this.gsi2Sk = gsi2Sk;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = DynamoDbKeys.GSI3_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI3_PARTITION_KEY)
    public String getGsi3Pk() {
        return gsi3Pk;
    }

    public void setGsi3Pk(String gsi3Pk) {
        this.gsi3Pk = gsi3Pk;
    }

    @DynamoDbSecondarySortKey(indexNames = DynamoDbKeys.GSI3_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI3_SORT_KEY)
    public String getGsi3Sk() {
        return gsi3Sk;
    }

    public void setGsi3Sk(String gsi3Sk) {
        this.gsi3Sk = gsi3Sk;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = DynamoDbKeys.GSI4_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI4_PARTITION_KEY)
    public String getGsi4Pk() {
        return gsi4Pk;
    }

    public void setGsi4Pk(String gsi4Pk) {
        this.gsi4Pk = gsi4Pk;
    }

    @DynamoDbSecondarySortKey(indexNames = DynamoDbKeys.GSI4_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI4_SORT_KEY)
    public String getGsi4Sk() {
        return gsi4Sk;
    }

    public void setGsi4Sk(String gsi4Sk) {
        this.gsi4Sk = gsi4Sk;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = DynamoDbKeys.GSI5_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI5_PARTITION_KEY)
    public String getGsi5Pk() {
        return gsi5Pk;
    }

    public void setGsi5Pk(String gsi5Pk) {
        this.gsi5Pk = gsi5Pk;
    }

    @DynamoDbSecondarySortKey(indexNames = DynamoDbKeys.GSI5_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI5_SORT_KEY)
    public String getGsi5Sk() {
        return gsi5Sk;
    }

    public void setGsi5Sk(String gsi5Sk) {
        this.gsi5Sk = gsi5Sk;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = DynamoDbKeys.GSI6_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI6_PARTITION_KEY)
    public String getGsi6Pk() {
        return gsi6Pk;
    }

    public void setGsi6Pk(String gsi6Pk) {
        this.gsi6Pk = gsi6Pk;
    }

    @DynamoDbSecondarySortKey(indexNames = DynamoDbKeys.GSI6_NAME)
    @DynamoDbAttribute(DynamoDbKeys.GSI6_SORT_KEY)
    public String getGsi6Sk() {
        return gsi6Sk;
    }

    public void setGsi6Sk(String gsi6Sk) {
        this.gsi6Sk = gsi6Sk;
    }
}
