package com.argocd.platform.api.exception;

/**
 * Thrown when a requested resource (cluster, control plane, project, application)
 * cannot be found in the database.
 *
 * <p>HTTP status {@code 404 Not Found} is produced by {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
