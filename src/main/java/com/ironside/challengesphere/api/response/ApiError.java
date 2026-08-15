package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.sql.Timestamp;
import java.time.Instant;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private Timestamp timestamp;
    private String errorMessage;
    private String debugMessage;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int errorCode;
    private String code;
    private Boolean upgradeRequired;
    private String tier;
    private String challengeVisibility;
    private Long currentCount;
    private Integer challengeLimit;

    public ApiError() {
        this.timestamp = Timestamp.from(Instant.now());
    }

    public ApiError(Timestamp timestamp, String errorMessage, String debugMessage, int errorCode) {
        this.timestamp = timestamp;
        this.errorMessage = errorMessage;
        this.debugMessage = debugMessage;
        this.errorCode = errorCode;
    }
}
