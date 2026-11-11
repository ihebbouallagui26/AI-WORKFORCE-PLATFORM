package com.aiworkforce.core.common.exception;

import com.aiworkforce.core.common.logging.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place where exceptions become HTTP responses, as RFC 9457 problem details
 * ({@code application/problem+json}).
 *
 * <p>Every problem carries a stable {@code type} URN, the request path as {@code instance},
 * a UTC {@code timestamp} and the {@code requestId} used in server logs. Stack traces,
 * exception messages of unexpected errors and database details are never returned.
 *
 * <p>Spring Security's entry point and access-denied handler delegate here as well, so
 * 401/403 responses have exactly the same shape (see {@code SecurityConfig}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String AUTHENTICATE_CHALLENGE = "Bearer realm=\"ai-workforce\"";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** Bean Validation failures on {@code @Valid @RequestBody} payloads. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), messageOf(error)))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION,
                "The request contains invalid fields.", request);
        problem.setProperty("errors", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Bean Validation failures on method parameters ({@code @RequestParam}, {@code @PathVariable}). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION,
                "The request contains invalid parameters.", request);
        problem.setProperty("errors", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Validation performed in services (e.g. {@code @Validated} beans). */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(lastNode(violation.getPropertyPath().toString()),
                        violation.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION,
                "The request contains invalid values.", request);
        problem.setProperty("errors", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND,
                "The requested " + ex.resourceType() + " was not found.", request));
    }

    /** Also reached from the security filter chain for unauthenticated requests. */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, AUTHENTICATE_CHALLENGE)
                .body(problem(HttpStatus.UNAUTHORIZED, ProblemType.UNAUTHORIZED,
                        "Authentication is required to access this resource.", request));
    }

    /** Also reached from the security filter chain and from method security ({@code @PreAuthorize}). */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(HttpStatus.FORBIDDEN, ProblemType.FORBIDDEN,
                "You are not allowed to perform this operation.", request));
    }

    /** Last resort: log the full error server-side, return nothing internal to the client. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception while processing {}", path(request), ex);
        return ResponseEntity.internalServerError().body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemType.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the request id when contacting support.", request));
    }

    /**
     * Normalises the problem details produced by Spring MVC for standard exceptions
     * (malformed JSON, unsupported method or media type, missing parameters...).
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail existing
                ? existing
                : ProblemDetail.forStatus(statusCode);
        decorate(problem, statusCode, ProblemType.forStatus(statusCode.value()), request);
        if (statusCode.is5xxServerError()) {
            log.error("Request failed with status {} on {}", statusCode.value(), path(request), ex);
            problem.setDetail("An unexpected error occurred. Quote the request id when contacting support.");
        }
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    private ProblemDetail problem(HttpStatus status, ProblemType type, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        decorate(problem, status, type, request);
        return problem;
    }

    private void decorate(ProblemDetail problem, HttpStatusCode status, ProblemType type, WebRequest request) {
        problem.setType(type.uri());
        problem.setTitle(type.title());
        problem.setStatus(status.value());
        problem.setInstance(URI.create(path(request)));
        problem.setProperty("timestamp", Instant.now(clock));
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        Map<String, Object> properties = problem.getProperties();
        if (properties != null) {
            // Never leak framework internals that some Spring exceptions attach.
            properties.remove("exception");
            properties.remove("trace");
        }
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : "";
    }

    private static String messageOf(FieldError error) {
        return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
    }

    private static String lastNode(String propertyPath) {
        int index = propertyPath.lastIndexOf('.');
        return index < 0 ? propertyPath : propertyPath.substring(index + 1);
    }

    /** One invalid input. The rejected value is deliberately not echoed (it may be sensitive). */
    public record FieldViolation(String field, String message) {
    }
}
