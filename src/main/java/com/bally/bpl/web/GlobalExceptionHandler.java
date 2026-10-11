package com.bally.bpl.web;

import com.bally.bpl.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Maps every failure to an RFC 7807 {@code application/problem+json} body with a stable {@code code}.
 * Internal details (stack traces, SQL, constraint names) are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex, HttpServletRequest request) {
        return problem(ex.status(), ex.code(), ex.getMessage(), request.getRequestURI());
    }

    /** A unique / FK / check constraint fired: the application-level checks were bypassed or raced. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "CONFLICT",
                "The request conflicts with the current state of the resource", request.getRequestURI());
    }

    /** Row lock could not be obtained within LOCK_TIMEOUT: tell the client to retry. */
    @ExceptionHandler({CannotAcquireLockException.class, PessimisticLockingFailureException.class,
            ObjectOptimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> handleLock(Exception ex, HttpServletRequest request) {
        log.warn("Lock contention on {}: {}", request.getRequestURI(), ex.getMessage());
        ProblemDetail body = problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE",
                "The resource is busy, please retry", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(body);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request.getRequestURI());
    }

    // ------------------------------------------------------------ framework exceptions

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getAllErrors().stream()
                .map(e -> (e instanceof FieldError fe ? fe.getField() : e.getObjectName()) + ": " + e.getDefaultMessage())
                .sorted()
                .toList();
        return validationResponse(errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getAllErrors().stream()
                .map(e -> String.valueOf(e.getDefaultMessage()))
                .sorted()
                .toList();
        return validationResponse(errors, headers, request);
    }

    private ResponseEntity<Object> validationResponse(List<String> errors, HttpHeaders headers, WebRequest request) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", path(request));
        pd.setProperty("errors", errors);
        return handleExceptionInternal(new IllegalArgumentException("validation"), pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // Give framework-generated problems (405, 415, malformed JSON, ...) the same shape as ours.
        if (body instanceof ProblemDetail pd && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            pd.setProperty("code", HttpStatus.valueOf(statusCode.value()).name());
            pd.setProperty("path", path(request));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // ------------------------------------------------------------ helpers

    private static ProblemDetail problem(HttpStatus status, String code, String detail, String path) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        pd.setProperty("path", path);
        return pd;
    }

    private static String path(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=") ? description.substring(4) : description;
    }
}
