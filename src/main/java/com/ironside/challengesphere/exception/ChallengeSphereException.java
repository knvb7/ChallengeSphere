package com.ironside.challengesphere.exception;


import com.ironside.challengesphere.api.response.ApiError;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;

import java.io.Serial;
import java.sql.Timestamp;
import java.time.Instant;

public class ChallengeSphereException extends BaseException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ChallengeSphereException(String message, HttpStatus status, Logger logger){
        if (isServerError(status)) {
            logger.error("Message is : {} , Status is : {}", message, status);
        } else {
            logger.warn("Message is : {} , Status is : {}", message, status);
        }
        this.setStatus(status);
        this.setResponseMessage(message);
        this.setApiError(createApiError(message));
    }

    public ChallengeSphereException(String errorMessage, HttpStatus status, Logger logger, Throwable ex){
        if (isServerError(status)) {
            logger.error("Message is : {} , Status is : {}", errorMessage, status, ex);
        } else {
            logger.warn("Message is : {} , Status is : {}", errorMessage, status);
        }
        this.setStatus(status);
        this.setE(ex);
        this.setResponseMessage(errorMessage);
        this.setApiError(createApiError(errorMessage));
    }

    public HttpStatus getStatus(){
        return status;
    }
    private ApiError createApiError(String message) {
        return new ApiError(Timestamp.from(Instant.now()), message, null, 0);
    }

    private boolean isServerError(HttpStatus status) {
        return status != null && status.is5xxServerError();
    }
}
