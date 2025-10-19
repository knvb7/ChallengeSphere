package com.ironside.challengesphere.exception;

import com.ironside.challengesphere.api.response.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.security.sasl.AuthenticationException;
import java.nio.file.AccessDeniedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LogManager.getLogger(GlobalExceptionHandler.class);

    // Handle BaseException (Custom exceptions)
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiError> handleBaseException(BaseException ex, WebRequest request) {
        logger.error("BaseException occurred: {}", ex.getResponseMessage(), ex);

        ApiError apiError = ex.getApiError();
        if (apiError == null) {
            apiError = new ApiError();
            apiError.setErrorMessage(ex.getResponseMessage());
            apiError.setTimestamp(Timestamp.from(Instant.now()));
        }

        HttpStatus status = ex.getStatus() != null ? ex.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
        return new ResponseEntity<>(apiError, status);
    }

    // Handle Validation Errors
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationExceptions(MethodArgumentNotValidException ex) {
        logger.error("Validation error occurred: {}", ex.getMessage());

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Validation failed");
        apiError.setDebugMessage(errors.toString());
        apiError.setErrorCode(400);

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    // Handle Constraint Violation Exceptions
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolationException(ConstraintViolationException ex) {
        logger.error("Constraint violation occurred: {}", ex.getMessage());

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Constraint violation");
        apiError.setDebugMessage(ex.getMessage());
        apiError.setErrorCode(400);

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    // Handle Authentication Exceptions
    @ExceptionHandler({AuthenticationException.class})
    public ResponseEntity<ApiError> handleAuthenticationException(AuthenticationException ex) {
        logger.error("Authentication error occurred: {}", ex.getMessage());

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Authentication failed");
        apiError.setDebugMessage(ex.getMessage());
        apiError.setErrorCode(401);

        return new ResponseEntity<>(apiError, HttpStatus.UNAUTHORIZED);
    }

    // Handle Access Denied Exceptions
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDeniedException(AccessDeniedException ex) {
        logger.error("Access denied: {}", ex.getMessage());

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Access denied");
        apiError.setDebugMessage(ex.getMessage());
        apiError.setErrorCode(403);

        return new ResponseEntity<>(apiError, HttpStatus.FORBIDDEN);
    }

    // Handle Data Integrity Violation (Database constraint violations)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolationException(DataIntegrityViolationException ex) {
        logger.error("Data integrity violation: {}", ex.getMessage());

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Data integrity violation");
        apiError.setDebugMessage("Duplicate entry or constraint violation");
        apiError.setErrorCode(409);

        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    // Handle Missing Request Parameters
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex) {
        logger.error("Missing request parameter: {}", ex.getMessage());

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Missing required parameter");
        apiError.setDebugMessage("Required parameter '" + ex.getParameterName() + "' is missing");
        apiError.setErrorCode(400);

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    // Handle Method Argument Type Mismatch
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        logger.error("Method argument type mismatch: {}", ex.getMessage());

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Invalid parameter type");
        apiError.setDebugMessage("Parameter '" + ex.getName() + "' should be of type " + ex.getRequiredType());
        apiError.setErrorCode(400);

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    // Handle HTTP Message Not Readable (Malformed JSON)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        logger.error("HTTP message not readable: {}", ex.getMessage());

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Malformed JSON request");
        apiError.setDebugMessage("Request body is not readable or malformed");
        apiError.setErrorCode(400);

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    // Handle IllegalArgumentException
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.error("Illegal argument: {}", ex.getMessage());

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Invalid argument");
        apiError.setDebugMessage(ex.getMessage());
        apiError.setErrorCode(400);

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    // Handle IllegalStateException
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalStateException(IllegalStateException ex) {
        logger.error("Illegal state: {}", ex.getMessage());

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Invalid operation state");
        apiError.setDebugMessage(ex.getMessage());
        apiError.setErrorCode(409);

        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    // Handle NullPointerException
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ApiError> handleNullPointerException(NullPointerException ex) {
        logger.error("Null pointer exception occurred: {}", ex.getMessage(), ex);

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Internal server error");
        apiError.setDebugMessage("A null pointer exception occurred");
        apiError.setErrorCode(500);

        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // Handle all other exceptions
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGlobalException(Exception ex, WebRequest request) {
        logger.error("Unexpected error occurred: {}", ex.getMessage(), ex);

        ApiError apiError = new ApiError();
        apiError.setErrorMessage("Internal server error");
        apiError.setDebugMessage("An unexpected error occurred");
        apiError.setErrorCode(500);

        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}