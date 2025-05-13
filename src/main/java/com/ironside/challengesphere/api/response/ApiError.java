package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.sql.Timestamp;
import java.time.Instant;

@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private Timestamp timestamp;
    private String errorMessage;
    private String debugMessage;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int errorCode;
    public ApiError() {
        this.timestamp = Timestamp.from(Instant.now());
    }
}