package com.argocd.platform.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates that every key and value in a {@code Map<String, String>} complies with
 * the <a href="https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/">
 * Kubernetes label format</a>.
 *
 * <h3>Key format: {@code [prefix/]name}</h3>
 * <ul>
 *   <li><b>prefix</b> (optional): DNS subdomain — alphanumeric, {@code -}, {@code .};
 *       starts and ends with alphanumeric; max 253 chars.</li>
 *   <li><b>name</b>: alphanumeric, {@code -}, {@code _}, {@code .};
 *       starts and ends with alphanumeric; max 63 chars.</li>
 * </ul>
 *
 * <h3>Value format</h3>
 * <ul>
 *   <li>Empty string is valid.</li>
 *   <li>Non-empty: alphanumeric, {@code -}, {@code _}, {@code .};
 *       starts and ends with alphanumeric; max 63 chars.</li>
 * </ul>
 *
 * <p>A {@code null} or empty map passes validation without error.
 */
public class K8sLabelsValidator
        implements ConstraintValidator<ValidK8sLabels, Map<String, String>> {

    /** Matches a valid label name/value segment: alphanumeric + [-_.], starts/ends alphanum. */
    private static final Pattern LABEL_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9\\-_.]*[a-zA-Z0-9])?$");

    /**
     * DNS subdomain: alphanumeric and {@code -.}, starts/ends with alphanumeric.
     * Dots separate valid DNS labels.
     */
    private static final Pattern DNS_SUBDOMAIN_PATTERN =
            Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9\\-.]*[a-zA-Z0-9])?$");

    private static final int MAX_NAME_LEN   = 63;
    private static final int MAX_PREFIX_LEN = 253;

    @Override
    public boolean isValid(Map<String, String> labels, ConstraintValidatorContext context) {
        if (labels == null || labels.isEmpty()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        boolean valid = true;

        for (Map.Entry<String, String> entry : labels.entrySet()) {
            String keyError = validateKey(entry.getKey());
            if (keyError != null) {
                context.buildConstraintViolationWithTemplate(
                        "Invalid label key '" + entry.getKey() + "': " + keyError)
                        .addConstraintViolation();
                valid = false;
                continue;
            }
            String valueError = validateValue(entry.getValue());
            if (valueError != null) {
                context.buildConstraintViolationWithTemplate(
                        "Invalid label value for key '" + entry.getKey() + "': " + valueError)
                        .addConstraintViolation();
                valid = false;
            }
        }
        return valid;
    }

    /**
     * Returns {@code null} if the key is valid, otherwise an error description.
     */
    private String validateKey(String key) {
        if (key == null || key.isEmpty()) {
            return "key must not be blank";
        }

        int slashIndex = key.indexOf('/');
        if (slashIndex >= 0) {
            // Key has a prefix
            String prefix = key.substring(0, slashIndex);
            String name   = key.substring(slashIndex + 1);

            if (prefix.isEmpty()) {
                return "prefix (before '/') must not be empty";
            }
            if (prefix.length() > MAX_PREFIX_LEN) {
                return "prefix '" + prefix + "' exceeds max " + MAX_PREFIX_LEN + " chars";
            }
            if (!DNS_SUBDOMAIN_PATTERN.matcher(prefix).matches()) {
                return "prefix '" + prefix + "' is not a valid DNS subdomain "
                        + "(alphanumeric, '-', '.'; start/end alphanumeric)";
            }
            return validateName(name);
        }

        return validateName(key);
    }

    /**
     * Returns {@code null} if the name segment is valid, otherwise an error description.
     */
    private String validateName(String name) {
        if (name == null || name.isEmpty()) {
            return "name must not be blank";
        }
        if (name.length() > MAX_NAME_LEN) {
            return "name '" + name + "' exceeds max " + MAX_NAME_LEN + " chars";
        }
        if (!LABEL_NAME_PATTERN.matcher(name).matches()) {
            return "name '" + name + "' must consist of alphanumeric characters, '-', '_', or '.', "
                    + "and must start and end with an alphanumeric character";
        }
        return null;
    }

    /**
     * Returns {@code null} if the value is valid, otherwise an error description.
     */
    private String validateValue(String value) {
        if (value == null || value.isEmpty()) {
            return null; // empty string is explicitly allowed by K8s spec
        }
        if (value.length() > MAX_NAME_LEN) {
            return "value '" + value + "' exceeds max " + MAX_NAME_LEN + " chars";
        }
        if (!LABEL_NAME_PATTERN.matcher(value).matches()) {
            return "value '" + value + "' must consist of alphanumeric characters, '-', '_', or '.', "
                    + "and must start and end with an alphanumeric character";
        }
        return null;
    }
}
