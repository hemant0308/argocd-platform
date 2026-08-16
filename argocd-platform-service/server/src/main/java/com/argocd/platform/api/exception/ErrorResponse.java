package com.argocd.platform.api.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Uniform error envelope returned by {@link GlobalExceptionHandler} for every
 * non-2xx response. The {@code errors} list is populated only for validation
 * failures ({@code 400}) and is omitted ({@code null}) otherwise.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /** ISO-8601 timestamp of when the error was generated. */
    private LocalDateTime timestamp;

    /** HTTP status code (e.g. 400, 404, 500). */
    private int status;

    /** HTTP status reason phrase (e.g. "Bad Request", "Not Found"). */
    private String error;

    /** Human-readable summary of what went wrong. */
    private String message;

    /** Request URI that triggered the error. */
    private String path;

    /**
     * Field-level validation errors. Present only for {@code 400} validation failures.
     * Each entry names the offending field and explains the constraint that was violated.
     * {@code null} for all non-validation error types.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<FieldError> errors;

    // -------------------------------------------------------------------------

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldError {

        /**
         * Name of the request field that failed validation.
         * {@code null} for object-level (cross-field) constraint violations.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String field;

        /** Human-readable reason the constraint was violated. */
        private String message;
    }
}
