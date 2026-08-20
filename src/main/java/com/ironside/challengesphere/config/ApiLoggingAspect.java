package com.ironside.challengesphere.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironside.challengesphere.exception.BaseException;
import com.ironside.challengesphere.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Iterator;
import java.util.Map;

/**
 * AOP Aspect that logs every API call across all controllers.
 * Logs stable route metadata, a bounded redacted request body, response status,
 * exception details, and execution time.
 */
@Aspect
@Component
public class ApiLoggingAspect {

    private static final Logger logger = LogManager.getLogger(ApiLoggingAspect.class);
    private static final int MAX_EXCEPTION_DETAIL_LENGTH = 500;
    private static final int MAX_REQUEST_PAYLOAD_LENGTH = 2_000;
    private static final String REDACTED_VALUE = "[REDACTED]";

    private final ObjectMapper objectMapper;

    public ApiLoggingAspect(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Pointcut matching all public methods in any class under the controller package
     */
    @Pointcut("execution(public * com.ironside.challengesphere.controller..*(..))")
    public void controllerMethods() {}

    /**
     * Pointcut matching methods annotated with any Spring web mapping annotation
     */
    @Pointcut("@annotation(org.springframework.web.bind.annotation.GetMapping) || " +
              "@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
              "@annotation(org.springframework.web.bind.annotation.PutMapping) || " +
              "@annotation(org.springframework.web.bind.annotation.DeleteMapping) || " +
              "@annotation(org.springframework.web.bind.annotation.PatchMapping) || " +
              "@annotation(org.springframework.web.bind.annotation.RequestMapping)")
    public void requestMappingMethods() {}

    /**
     * Around advice that logs before and after every API endpoint execution
     */
    @Around("controllerMethods() && requestMappingMethods()")
    public Object logApiCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();

        // Extract method and class info
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = method.getName();

        // Extract HTTP method and URI from the current request
        String httpMethod = "UNKNOWN";
        String routePattern = "UNKNOWN";

        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            httpMethod = request.getMethod();
            Object matchedPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (matchedPattern instanceof String pattern && !pattern.isBlank()) {
                routePattern = pattern;
            }
        }

        String requestPayload = safeRequestPayload(method, joinPoint.getArgs());
        String userId = currentUserId();
        logger.info("API called | method={} route={} handler={}.{} userId={} payload={}",
                httpMethod, routePattern, className, methodName, userId, requestPayload);

        Object result;
        try {
            result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            // Extract HTTP status from ResponseEntity if available
            String status = "200";
            if (result instanceof ResponseEntity<?> responseEntity) {
                status = String.valueOf(responseEntity.getStatusCode().value());
            }

            logger.info("API completed | method={} route={} handler={}.{} userId={} status={} durationMs={}",
                    httpMethod, routePattern, className, methodName, userId, status, duration);

            return result;
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;

            HttpStatus status = ex instanceof BaseException baseException && baseException.getStatus() != null
                    ? baseException.getStatus()
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            String exceptionDetail = safeExceptionDetail(ex);
            if (status.is5xxServerError()) {
                Throwable loggedThrowable = loggingThrowable(ex);
                logger.error("API failed | method={} route={} handler={}.{} userId={} status={} exception={} reason={} durationMs={}",
                        httpMethod, routePattern, className, methodName, userId, status.value(),
                        ex.getClass().getSimpleName(), exceptionDetail, duration, loggedThrowable);
            } else {
                logger.warn("API rejected | method={} route={} handler={}.{} userId={} status={} exception={} reason={} durationMs={}",
                        httpMethod, routePattern, className, methodName, userId, status.value(),
                        ex.getClass().getSimpleName(), exceptionDetail, duration);
            }
            throw ex;
        }
    }

    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.getId() != null) {
            return principal.getId().toString();
        }
        return "anonymous";
    }

    static String safeExceptionDetail(Exception exception) {
        String detail = exception instanceof BaseException baseException
                ? baseException.getResponseMessage()
                : exception.getMessage();
        if (detail == null || detail.isBlank()) {
            return "No exception detail available";
        }

        String sanitized = detail
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
        if (sanitized.length() <= MAX_EXCEPTION_DETAIL_LENGTH) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_EXCEPTION_DETAIL_LENGTH) + "...";
    }

    static Throwable loggingThrowable(Exception exception) {
        if (exception instanceof BaseException baseException && baseException.getE() != null) {
            return baseException.getE();
        }
        return exception;
    }

    String safeRequestPayload(Method method, Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return "none";
        }

        try {
            Parameter[] parameters = method.getParameters();
            JsonNode payload = null;
            for (int index = 0; index < Math.min(parameters.length, arguments.length); index++) {
                if (!parameters[index].isAnnotationPresent(RequestBody.class)) {
                    continue;
                }

                Object argument = arguments[index];
                if (argument == null) {
                    payload = objectMapper.nullNode();
                } else {
                    payload = objectMapper.valueToTree(argument);
                    redactSensitiveFields(payload);
                }
                break;
            }

            if (payload == null) {
                return "none";
            }

            String serialized = payload.toString()
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .replace('\t', ' ');
            if (serialized.length() <= MAX_REQUEST_PAYLOAD_LENGTH) {
                return serialized;
            }
            return serialized.substring(0, MAX_REQUEST_PAYLOAD_LENGTH) + "...";
        } catch (RuntimeException serializationFailure) {
            return "[UNAVAILABLE:" + serializationFailure.getClass().getSimpleName() + "]";
        }
    }

    private void redactSensitiveFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveField(field.getKey())) {
                    objectNode.put(field.getKey(), REDACTED_VALUE);
                } else {
                    redactSensitiveFields(field.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::redactSensitiveFields);
        }
    }

    private boolean isSensitiveField(String fieldName) {
        String normalized = fieldName == null
                ? ""
                : fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("authorization")
                || normalized.contains("apikey")
                || normalized.contains("otp")
                || normalized.contains("code")
                || normalized.contains("email")
                || normalized.contains("phone")
                || normalized.contains("address")
                || normalized.contains("url")
                || normalized.contains("cookie");
    }
}
