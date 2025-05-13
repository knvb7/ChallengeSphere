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
        logger.error("Message is : {} , Status is : {}", message, status);
        this.setStatus(status);
        this.setResponseMessage(message);
        this.setApiError(createApiError(message, null));
    }

    public ChallengeSphereException(String errorMessage, HttpStatus status, Logger logger, Throwable ex){
        logger.error("Message is : {} , Status is : {}, stackTrace is : {}", errorMessage, status, ex.getStackTrace());
        this.setStatus(status);
        this.setE(ex);
        this.setResponseMessage(errorMessage);
        this.setApiError(createApiError(errorMessage, null));
    }

    public HttpStatus getStatus(){
        return status;
    }
    private ApiError createApiError(String message, String debugMessage) {
        return new ApiError(Timestamp.from(Instant.now()), message, debugMessage, 0);
    }
}
