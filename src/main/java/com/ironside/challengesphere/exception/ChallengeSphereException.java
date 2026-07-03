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
        this.setStatus(status);
        this.setResponseMessage(message);
        this.setApiError(createApiError(message));
    }

    public ChallengeSphereException(String errorMessage, HttpStatus status, Logger logger, Throwable ex){
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

}
