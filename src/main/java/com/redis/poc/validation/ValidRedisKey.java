package com.redis.poc.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = RedisKeyValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidRedisKey {
    String message() default "Invalid Redis key format";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
