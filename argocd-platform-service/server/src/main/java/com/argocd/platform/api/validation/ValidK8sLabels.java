package com.argocd.platform.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that every entry in a {@code Map<String, String>} conforms to the
 * Kubernetes label key/value format rules.
 *
 * <h3>Label key rules</h3>
 * <pre>
 *   [prefix/]name
 *   prefix — optional DNS subdomain, max 253 chars
 *   name   — [a-zA-Z0-9_.-], starts/ends with alphanumeric, max 63 chars
 * </pre>
 *
 * <h3>Label value rules</h3>
 * <pre>
 *   Empty string is allowed.
 *   If non-empty: [a-zA-Z0-9_.-], starts/ends with alphanumeric, max 63 chars.
 * </pre>
 *
 * <p>A {@code null} or empty map passes validation.
 */
@Documented
@Constraint(validatedBy = K8sLabelsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidK8sLabels {

    String message() default "Labels must follow Kubernetes label key/value conventions "
            + "(key: [prefix/]name where name is alphanumeric/_/./- max 63 chars, "
            + "optional prefix is DNS subdomain max 253 chars; "
            + "value: empty or alphanumeric/_/./- max 63 chars)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
