package com.urlshortener.link;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a Destination: a long public web URL over http or https
 * (GLOSSARY). Anything else fails request validation with a {@code 400}.
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
