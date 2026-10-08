package com.urlshortener.link;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a Destination: a long public web URL — absolute http or
 * https with a host, on a public host, at most 2048 characters (GLOSSARY,
 * issue #4). Anything else fails request validation with a {@code 400}
 * problem+json that reports every broken rule's type code (D10).
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DestinationValidator.class)
public @interface Destination {

    String message() default "must be an absolute http or https URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
