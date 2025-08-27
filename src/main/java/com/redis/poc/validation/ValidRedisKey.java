package com.redis.poc.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/**
 * Annotation to validate a Redis key.
 * The key must not be null or empty, and must not contain spaces.
 */
@Documented
@Constraint(validatedBy = RedisKeyValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidRedisKey {
    /**
     * @return the error message template
     */
    String message() default "Invalid Redis key format";

    /**
     * @return the validation groups
     */
    Class<?>[] groups() default {};

    /**
     * @return the payload
     */
    Class<? extends Payload>[] payload() default {};
}
