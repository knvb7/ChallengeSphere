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
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * AOP Aspect that logs every API call across all controllers.
 * Logs only stable route metadata, response status, and execution time.
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

        logger.info("API called | method={} route={} handler={}.{}",
                httpMethod, routePattern, className, methodName);

        Object result;
        try {
            result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            // Extract HTTP status from ResponseEntity if available
            String status = "200";
            if (result instanceof ResponseEntity<?> responseEntity) {
                status = String.valueOf(responseEntity.getStatusCode().value());
            }

            logger.info("API completed | method={} route={} handler={}.{} status={} durationMs={}",
                    httpMethod, routePattern, className, methodName, status, duration);

            return result;
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;

            HttpStatus status = ex instanceof BaseException baseException && baseException.getStatus() != null
                    ? baseException.getStatus()
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            if (status.is5xxServerError()) {
                logger.error("API failed | method={} route={} handler={}.{} status={} exception={} durationMs={}",
                        httpMethod, routePattern, className, methodName, status.value(),
                        ex.getClass().getSimpleName(), duration);
            } else {
                logger.warn("API rejected | method={} route={} handler={}.{} status={} exception={} durationMs={}",
                        httpMethod, routePattern, className, methodName, status.value(),
                        ex.getClass().getSimpleName(), duration);
            }

            throw ex;
        }
    }

}
