package com.redis.poc.exception;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/**
 * A centralized exception handler for the entire application.
 * Annotated with @ControllerAdvice, this class intercepts exceptions thrown by controllers
 * and provides a consistent, structured JSON error response, improving API predictability and client-side handling.
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handles validation errors for @Valid annotated request bodies.
     * Returns a 400 Bad Request with a map of fields and their corresponding validation error messages.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));

        ErrorResponse body = baseBuilder(HttpStatus.BAD_REQUEST, "Validation failed", request)
                .validationErrors(errors)
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handles validation errors for @Validated annotated path variables or request parameters.
     * Returns a 400 Bad Request.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, WebRequest request) {
        ErrorResponse body =
                baseBuilder(HttpStatus.BAD_REQUEST, ex.getMessage(), request).build();
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handles authentication failures (e.g., incorrect username or password).
     * Returns a 401 Unauthorized.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, WebRequest request) {
        ErrorResponse body = baseBuilder(HttpStatus.UNAUTHORIZED, "Invalid credentials provided", request)
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Handles authorization failures where an authenticated user lacks the required roles or permissions.
     * Returns a 403 Forbidden.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        ErrorResponse body =
                baseBuilder(HttpStatus.FORBIDDEN, "Access is denied", request).build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Handles failures to connect to the Redis server.
     * Returns a 503 Service Unavailable to indicate the caching service is down.
     */
    @ExceptionHandler(RedisConnectionException.class)
    public ResponseEntity<ErrorResponse> handleRedisConnection(RedisConnectionException ex, WebRequest request) {
        log.error("Redis connection error: {}. Path: {}", ex.getMessage(), getPath(request));
        ErrorResponse body = baseBuilder(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The cache service is currently unavailable. Please try again later.",
                        request)
                .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Handles timeouts during a Redis command execution.
     * Returns a 408 Request Timeout.
     */
    @ExceptionHandler(RedisCommandTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleRedisTimeout(RedisCommandTimeoutException ex, WebRequest request) {
        log.error("Redis command timed out: {}. Path: {}", ex.getMessage(), getPath(request));
        ErrorResponse body = baseBuilder(
                        HttpStatus.REQUEST_TIMEOUT, "The cache operation timed out. Please try again.", request)
                .build();
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(body);
    }

    /**
     * Handles illegal arguments, typically caused by invalid client input that is not caught by validation.
     * Returns a 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        ErrorResponse body =
                baseBuilder(HttpStatus.BAD_REQUEST, ex.getMessage(), request).build();
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * A catch-all handler for any other unhandled exceptions.
     * Logs the full stack trace and returns a generic 500 Internal Server Error to avoid exposing internal details.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, WebRequest request) {
        String traceId = UUID.randomUUID().toString();
        log.error("Unhandled exception occurred with traceId: {}", traceId, ex);
        ErrorResponse body = baseBuilder(
                        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal error occurred.", request)
                .traceId(traceId) // Ensure traceId is set for the final response
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * A private helper to create a base ErrorResponse with common fields.
     * @param status The HTTP status.
     * @param message A descriptive error message.
     * @param request The original web request.
     * @return An ErrorResponseBuilder pre-populated with common details.
     */
    private ErrorResponse.ErrorResponseBuilder baseBuilder(HttpStatus status, String message, WebRequest request) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(getPath(request))
                .traceId(UUID.randomUUID().toString()); // Provides a unique ID for logging and client-side reporting
    }

    /**
     * Safely extracts the request URI from the WebRequest.
     */
    private String getPath(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return "N/A";
    }
}
