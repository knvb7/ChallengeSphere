package com.ironside.challengesphere.exception;

import com.ironside.challengesphere.api.response.ApiError;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.io.Serial;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class BaseException extends Exception{
    @Serial
    private static final long serialVersionUID = 1L;
    public String responseMessage;
    public HttpStatus status;
    public Throwable e;
    public ApiError apiError;
}
