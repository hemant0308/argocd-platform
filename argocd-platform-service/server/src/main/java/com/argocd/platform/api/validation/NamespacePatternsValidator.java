package com.argocd.platform.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates that every entry in a {@code List<String>} is a valid ArgoCD namespace pattern.
 *
 * <p>Rules (applied per entry):
 * <ol>
 *   <li>{@code *}  — standalone wildcard; valid as-is.</li>
 *   <li>Exact K8s namespace — RFC 1123 label: lowercase alphanumeric and {@code -},
 *       must start and end with an alphanumeric character, max 63 chars.</li>
 *   <li>Glob prefix — a valid lowercase alphanumeric/{@code -} prefix ending with {@code *},
 *       total length ≤ 63 chars (e.g. {@code team-*}).</li>
 * </ol>
 *
 * <p>A {@code null} or empty list passes without error.
 */
public class NamespacePatternsValidator
        implements ConstraintValidator<ValidNamespacePatterns, List<String>> {

    /** RFC 1123 label: lowercase alphanumeric and hyphens, starts/ends with alphanumeric. */
    private static final Pattern RFC_1123 =
            Pattern.compile("^[a-z0-9]([a-z0-9\\-]*[a-z0-9])?$");

    /**
     * Glob prefix pattern: starts with lowercase alphanumeric, may contain lowercase
     * alphanumeric and hyphens, ends with {@code *}.
     */
    private static final Pattern GLOB_PREFIX =
            Pattern.compile("^[a-z0-9][a-z0-9\\-]*\\*$");

    private static final int MAX_LEN = 63;

    @Override
    public boolean isValid(List<String> values, ConstraintValidatorContext context) {
        if (values == null || values.isEmpty()) {
            return true;
        }

        for (String value : values) {
            if (!isValidPattern(value)) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "Invalid namespace pattern: '" + value + "'. "
                                + "Must be '*', a valid K8s namespace name, or a glob prefix "
                                + "ending with '*' (e.g. 'team-*'), max " + MAX_LEN + " chars.")
                        .addConstraintViolation();
                return false;
            }
        }
        return true;
    }

    private boolean isValidPattern(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        // Standalone wildcard
        if ("*".equals(value)) {
            return true;
        }
        if (value.length() > MAX_LEN) {
            return false;
        }
        // Glob prefix (e.g. "team-*")
        if (value.endsWith("*")) {
            return GLOB_PREFIX.matcher(value).matches();
        }
        // Exact K8s namespace
        return RFC_1123.matcher(value).matches();
    }
}
