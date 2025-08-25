package com.redis.poc.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class RedisKeyValidator implements ConstraintValidator<ValidRedisKey, String> {
    
    private static final Pattern VALID_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9:._-]+$");
    private static final int MAX_KEY_LENGTH = 512;

    @Override
    public void initialize(ValidRedisKey constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(String key, ConstraintValidatorContext context) {
        if (key == null || key.trim().isEmpty()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Redis key cannot be null or empty")
                   .addConstraintViolation();
            return false;
        }

        if (key.length() > MAX_KEY_LENGTH) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Redis key length cannot exceed " + MAX_KEY_LENGTH + " characters")
                   .addConstraintViolation();
            return false;
        }

        if (!VALID_KEY_PATTERN.matcher(key).matches()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Redis key contains invalid characters. Only alphanumeric, colon, dot, underscore, and hyphen are allowed")
                   .addConstraintViolation();
            return false;
        }

        return true;
    }
}
