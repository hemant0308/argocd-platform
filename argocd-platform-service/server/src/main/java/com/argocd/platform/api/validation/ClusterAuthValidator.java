package com.argocd.platform.api.validation;

import com.argocd.platform.api.model.auth.AuthType;
import com.argocd.platform.api.model.auth.ClusterAuth;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates a {@link ClusterAuth} object.
 *
 * <p>Dispatch table — required fields per {@link AuthType}:
 * <table border="1">
 *   <tr><th>AuthType</th><th>Required fields</th></tr>
 *   <tr><td>BEARER</td><td>token (non-blank)</td></tr>
 * </table>
 *
 * <p>To add a new auth type: add a {@code case} branch in {@link #validate(ClusterAuth, ConstraintValidatorContext)}.
 */
public class ClusterAuthValidator implements ConstraintValidator<ValidClusterAuth, ClusterAuth> {

    @Override
    public boolean isValid(ClusterAuth auth, ConstraintValidatorContext context) {
        // null auth is allowed — auth is optional on the cluster
        if (auth == null) {
            return true;
        }
        return validate(auth, context);
    }

    private boolean validate(ClusterAuth auth, ConstraintValidatorContext context) {
        if (auth.getType() == null) {
            fail(context, "auth.type must not be null");
            return false;
        }

        return switch (auth.getType()) {
            case BEARER -> validateBearer(auth, context);
            // When adding a new AuthType, add a case branch here and document
            // its required fields in the ClusterAuth class.
        };
    }

    // -------------------------------------------------------------------------
    // Per-type validators
    // -------------------------------------------------------------------------

    private boolean validateBearer(ClusterAuth auth, ConstraintValidatorContext context) {
        if (auth.getToken() == null || auth.getToken().isBlank()) {
            fail(context, "auth.token is required for BEARER authentication");
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
    }
}
