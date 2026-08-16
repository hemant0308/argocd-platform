package com.argocd.platform.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that each entry in a {@code List<String>} is a valid ArgoCD cluster namespace pattern.
 *
 * <p>Valid patterns:
 * <ul>
 *   <li>{@code *}  — wildcard matching all namespaces</li>
 *   <li>A standard Kubernetes namespace name (RFC 1123 label: lowercase alphanumeric and hyphens,
 *       starts and ends with alphanumeric, max 63 chars)</li>
 *   <li>A namespace glob prefix followed by {@code *} (e.g. {@code team-*}), max 63 chars total</li>
 * </ul>
 *
 * <p>A {@code null} list or empty list passes validation.
 */
@Documented
@Constraint(validatedBy = NamespacePatternsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidNamespacePatterns {

    String message() default "Each namespace must be '*', a valid Kubernetes namespace name "
            + "(lowercase alphanumeric / hyphens, max 63 chars), "
            + "or a glob prefix ending with '*' (e.g. 'team-*')";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
