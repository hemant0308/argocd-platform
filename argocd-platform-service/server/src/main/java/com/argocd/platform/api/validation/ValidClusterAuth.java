package com.argocd.platform.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates a {@link com.argocd.platform.api.model.auth.ClusterAuth} object.
 *
 * <p>Rules:
 * <ul>
 *   <li>A {@code null} auth object passes validation (auth is optional).</li>
 *   <li>When present, {@code type} must not be {@code null}.</li>
 *   <li>{@code BEARER} — {@code token} must not be blank.</li>
 * </ul>
 *
 * <p>This annotation can be placed on the {@code ClusterAuth} class itself so that
 * it fires whenever a field of that type is validated (including nested within request
 * classes annotated with {@code @Valid}).
 */
@Documented
@Constraint(validatedBy = ClusterAuthValidator.class)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidClusterAuth {

    String message() default "Invalid cluster auth configuration";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
