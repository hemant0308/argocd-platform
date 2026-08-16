package com.argocd.platform.api.exception;

/**
 * Thrown when a request is syntactically valid but semantically incorrect —
 * for example, a cluster reference that specifies neither {@code id} nor {@code name}.
 *
 * <p>HTTP status {@code 400 Bad Request} is produced by {@link GlobalExceptionHandler}.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
