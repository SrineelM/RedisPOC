package com.redis.poc.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * Validator for Redis keys.
 * This validator checks if a given string is a valid Redis key based on the following criteria:
 * <ul>
 *     <li>Key is not null or empty</li>
 *     <li>Key length does not exceed 512 characters</li>
 *     <li>Key does not contain invalid characters (only alphanumeric, colon, dot, underscore, and hyphen are allowed)</li>
 * </ul>
 */
public class RedisKeyValidator implements ConstraintValidator<ValidRedisKey, String> {
    
    /**
     * Regex pattern for a valid Redis key.
     * Allows alphanumeric characters, colons, dots, underscores, and hyphens.
     */
    private static final Pattern VALID_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9:._-]+$");

    /**
     * Maximum allowed length for a Redis key.
     */
    private static final int MAX_KEY_LENGTH = 512;

    /**
     * Initializes the validator.
     * @param constraintAnnotation the annotation instance for this constraint
     */
    @Override
    public void initialize(ValidRedisKey constraintAnnotation) {
        // No initialization needed
    }

    /**
     * Validates the given Redis key.
     *
     * @param key     the Redis key to validate
     * @param context the validation context
     * @return {@code true} if the key is valid, {@code false} otherwise
     */
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
