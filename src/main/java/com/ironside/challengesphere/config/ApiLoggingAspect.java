package com.ironside.challengesphere.config;

import com.ironside.challengesphere.exception.BaseException;
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
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * AOP Aspect that logs every API call across all controllers.
 * Logs: HTTP method, URI, controller method, parameters, response status, and execution time.
 */
@Aspect
@Component
public class ApiLoggingAspect {

    private static final Logger logger = LogManager.getLogger(ApiLoggingAspect.class);

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
        String requestUri = "UNKNOWN";
        String queryString = "";
        String remoteAddr = "";

        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            httpMethod = request.getMethod();
            requestUri = request.getRequestURI();
            queryString = request.getQueryString() != null ? "?" + request.getQueryString() : "";
            remoteAddr = request.getRemoteAddr();
        }

        // Extract parameter names and values
        String[] paramNames = signature.getParameterNames();
        Object[] paramValues = joinPoint.getArgs();
        String params = formatParams(paramNames, paramValues);

        // Log the incoming request
        logger.info("▶ API CALLED  | {} {} {} | Controller: {}.{} | Params: [{}] | IP: {}",
                httpMethod, requestUri, queryString, className, methodName, params, remoteAddr);

        Object result;
        try {
            result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            // Extract HTTP status from ResponseEntity if available
            String status = "200";
            if (result instanceof ResponseEntity<?> responseEntity) {
                status = String.valueOf(responseEntity.getStatusCode().value());
            }

            logger.info("✔ API SUCCESS | {} {} | Controller: {}.{} | Status: {} | Duration: {} ms",
                    httpMethod, requestUri, className, methodName, status, duration);

            return result;
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;

            HttpStatus status = ex instanceof BaseException baseException && baseException.getStatus() != null
                    ? baseException.getStatus()
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            if (status.is5xxServerError()) {
                logger.error("✘ API FAILED  | {} {} | Controller: {}.{} | Exception: {} - {} | Duration: {} ms",
                        httpMethod, requestUri, className, methodName,
                        ex.getClass().getSimpleName(), ex.getMessage(), duration);
            } else {
                logger.warn("API rejected | {} {} | Controller: {}.{} | Status: {} | Exception: {} - {} | Duration: {} ms",
                        httpMethod, requestUri, className, methodName, status.value(),
                        ex.getClass().getSimpleName(), ex.getMessage(), duration);
            }

            throw ex;
        }
    }

    /**
     * Formats method parameter names and values for logging.
     * Skips large or sensitive objects; shows primitives, strings, and numeric types directly.
     */
    private String formatParams(String[] names, Object[] values) {
        if (names == null || names.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names[i]).append("=");
            if (values[i] == null) {
                sb.append("null");
            } else if (isSimpleType(values[i])) {
                sb.append(values[i]);
            } else {
                // For request bodies and complex objects, just log the class name to avoid dumping huge payloads
                sb.append(values[i].getClass().getSimpleName()).append("{...}");
            }
        }
        return sb.toString();
    }

    private boolean isSimpleType(Object obj) {
        return obj instanceof String
                || obj instanceof Number
                || obj instanceof Boolean
                || obj instanceof Enum
                || obj.getClass().isPrimitive();
    }
}

