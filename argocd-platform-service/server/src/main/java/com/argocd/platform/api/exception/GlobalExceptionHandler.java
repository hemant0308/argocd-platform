package com.argocd.platform.api.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Centralized exception handling for all controllers.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>{@code 400} — request body fails {@code @Valid} / {@code @Validated} constraints</li>
 *   <li>{@code 400} — malformed JSON body or unrecognized enum value in body</li>
 *   <li>{@code 400} — wrong type for a {@code @PathVariable} (e.g. non-UUID string)</li>
 *   <li>{@code 404} — business resource not found ({@link ResourceNotFoundException})</li>
 *   <li>{@code 404} — no handler registered for the request URI</li>
 *   <li>{@code 500} — catch-all for unhandled exceptions</li>
 * </ul>
 *
 * <p>Every response body uses {@link ErrorResponse}. Field-level validation failures
 * are returned in the {@code errors} array; all other errors carry only a top-level
 * {@code message}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // -------------------------------------------------------------------------
    // 400 — Request body validation failure (@Valid on @RequestBody)
    // -------------------------------------------------------------------------

    /**
     * Handles all bean-validation failures originating from {@code @Valid @RequestBody}.
     *
     * <p>Both field-level errors (e.g. {@code @NotBlank name}) and object/class-level
     * errors are collected and returned in the {@code errors} array.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        BindingResult result = ex.getBindingResult();

        // Field-level constraint violations (e.g. @NotBlank, @ValidNamespacePatterns, @ValidK8sLabels)
        List<ErrorResponse.FieldError> fieldErrors = result.getFieldErrors().stream()
                .map(fe -> ErrorResponse.FieldError.builder()
                        .field(fe.getField())
                        .message(fe.getDefaultMessage())
                        .build())
                .collect(Collectors.toList());

        // Object-level / cross-field constraint violations (class-level validators
        // that did not bind to a specific property path)
        List<ErrorResponse.FieldError> globalErrors = result.getGlobalErrors().stream()
                .map(ge -> ErrorResponse.FieldError.builder()
                        .message(ge.getDefaultMessage())
                        .build())
                .collect(Collectors.toList());

        List<ErrorResponse.FieldError> allErrors = Stream.concat(
                fieldErrors.stream(),
                globalErrors.stream()
        ).collect(Collectors.toList());

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed with " + allErrors.size() + " error(s)")
                .path(request.getRequestURI())
                .errors(allErrors)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // 400 — Malformed JSON or unrecognized enum value in request body
    // -------------------------------------------------------------------------

    /**
     * Handles cases where Jackson cannot parse the request body at all (syntax error)
     * or encounters an unrecognized value for an enum field (e.g. {@code "type": "INVALID"}).
     *
     * <p>For invalid enum values the response message names the offending field and
     * lists the accepted values so the caller can correct the request without reading docs.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        String message = buildNotReadableMessage(ex);
        log.debug("Unreadable HTTP message on {} {}: {}", request.getMethod(), request.getRequestURI(), message);

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    private String buildNotReadableMessage(HttpMessageNotReadableException ex) {
        if (!(ex.getCause() instanceof InvalidFormatException ife)) {
            return "Malformed or unreadable request body";
        }

        // Build a dotted field path from Jackson's reference chain (e.g. "auth.type")
        String fieldPath = ife.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));

        if (ife.getTargetType() != null && ife.getTargetType().isEnum()) {
            String accepted = Arrays.stream(ife.getTargetType().getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return String.format(
                    "Invalid value '%s' for field '%s'; accepted values: [%s]",
                    ife.getValue(), fieldPath, accepted);
        }

        return String.format(
                "Invalid value for field '%s': %s",
                fieldPath, ife.getOriginalMessage());
    }

    // -------------------------------------------------------------------------
    // 400 — Wrong type in @PathVariable (e.g. non-UUID string)
    // -------------------------------------------------------------------------

    /**
     * Handles type-conversion failures for {@code @PathVariable} parameters.
     * The most common case is a non-UUID string passed where a {@link java.util.UUID} is expected.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        String typeName = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "unknown";
        String message = String.format(
                "Invalid value '%s' for parameter '%s'; expected type: %s",
                ex.getValue(), ex.getName(), typeName);

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // 409 — Resource already exists (duplicate unique field)
    // -------------------------------------------------------------------------

    @ExceptionHandler(ResourceAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleResourceAlreadyExists(
            ResourceAlreadyExistsException ex,
            HttpServletRequest request) {

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Catches DB-level unique-constraint violations that slip past the service-layer
     * pre-check (e.g., concurrent inserts). Translates them to 409 so callers never
     * see a 500 for a duplicate-name race.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        String rootMsg = ex.getMostSpecificCause().getMessage();
        boolean isUniqueViolation = rootMsg != null
                && rootMsg.contains("duplicate key value violates unique constraint");

        if (isUniqueViolation) {
            log.debug("Unique constraint violation on {} {}: {}", request.getMethod(), request.getRequestURI(), rootMsg);
            ErrorResponse body = ErrorResponse.builder()
                    .timestamp(LocalDateTime.now())
                    .status(HttpStatus.CONFLICT.value())
                    .error(HttpStatus.CONFLICT.getReasonPhrase())
                    .message("A resource with the same unique identifier already exists.")
                    .path(request.getRequestURI())
                    .build();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        boolean isFkViolation = rootMsg != null
                && (rootMsg.contains("violates foreign key constraint")
                        || rootMsg.contains("is still referenced from table"));

        if (isFkViolation) {
            log.debug("FK constraint violation on {} {}: {}", request.getMethod(), request.getRequestURI(), rootMsg);
            ErrorResponse body = ErrorResponse.builder()
                    .timestamp(LocalDateTime.now())
                    .status(HttpStatus.CONFLICT.value())
                    .error(HttpStatus.CONFLICT.getReasonPhrase())
                    .message("Cannot delete resource: it is still referenced by other resources.")
                    .path(request.getRequestURI())
                    .build();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        log.error("Data integrity violation on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
                .message("An unexpected error occurred. Please contact the platform team.")
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // -------------------------------------------------------------------------
    // 400 — Semantically invalid request (business logic validation)
    // -------------------------------------------------------------------------

    /**
     * Handles {@link InvalidRequestException} thrown by service layer business-logic checks
     * (e.g. a cluster reference that provides neither id nor name).
     * These are distinct from bean-validation failures — they pass schema checks but fail
     * domain rules that can only be evaluated at runtime.
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            InvalidRequestException ex,
            HttpServletRequest request) {

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // 404 — Business resource not found
    // -------------------------------------------------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request) {

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error(HttpStatus.NOT_FOUND.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // -------------------------------------------------------------------------
    // 404 — No handler registered for the request URI / method
    // -------------------------------------------------------------------------

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex,
            HttpServletRequest request) {

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error(HttpStatus.NOT_FOUND.getReasonPhrase())
                .message("No endpoint found for "
                        + request.getMethod() + " " + request.getRequestURI())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // -------------------------------------------------------------------------
    // 500 — Catch-all for unhandled exceptions
    // -------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
                .message("An unexpected error occurred. Please contact the platform team.")
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
