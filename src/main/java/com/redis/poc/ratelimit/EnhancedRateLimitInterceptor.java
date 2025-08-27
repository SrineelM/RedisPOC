package com.redis.poc.ratelimit;

import com.redis.poc.audit.AuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A robust rate-limiting interceptor that uses Redis and a Lua script to implement
 * a token bucket algorithm. This interceptor provides a more sophisticated approach
 * to rate limiting by supporting different limits for authenticated and anonymous users,
 * logging audit trails, and adding informative rate limit headers to responses.
 *
 * The token bucket algorithm is used to control the rate of incoming requests. Each client
 * (identified by IP address or user ID) has a bucket of tokens that refills at a constant
 * rate.
 * Each request consumes one token from the bucket. If the bucket is empty, the request is
 * rejected. This approach allows for bursts of requests while still maintaining a long-term
 * average rate.
 *
 * The use of a Lua script executed on the Redis server ensures that the rate-limiting logic
 * is atomic and efficient, minimizing latency and preventing race conditions.
 */
@Component
@Slf4j
public class EnhancedRateLimitInterceptor implements HandlerInterceptor {

    // RedisTemplate for interacting with the Redis server.
    private final RedisTemplate<String, Object> redisTemplate;
    // RedisScript that holds the Lua script for the rate-limiting logic.
    private final DefaultRedisScript<Long> redisScript;
    // AuditLogger for logging security and operational events.
    private final AuditLogger auditLogger;

    // --- Rate Limiting Configuration for Anonymous Users ---

    // Maximum number of tokens in the bucket for anonymous users.
    @Value("${ratelimit.capacity:10}")
    private int capacity;

    // Rate at which tokens are refilled per second for anonymous users.
    @Value("${ratelimit.rate:5}")
    private int rate;

    // Time period for refilling tokens (e.g., "1s", "1m", "1h").
    @Value("${ratelimit.refill-period:1s}")
    private String refillPeriod;

    // --- Rate Limiting Configuration for Authenticated Users ---

    // Maximum number of tokens in the bucket for authenticated users.
    @Value("${ratelimit.user-capacity:50}")
    private int userCapacity;

    // Rate at which tokens are refilled per second for authenticated users.
    @Value("${ratelimit.user-rate:25}")
    private int userRate;

    /**
     * Constructs the interceptor, injecting dependencies and setting up the Lua script.
     *
     * @param redisTemplate The RedisTemplate for database interaction.
     * @param auditLogger   The logger for auditing events.
     */
    public EnhancedRateLimitInterceptor(RedisTemplate<String, Object> redisTemplate, AuditLogger auditLogger) {
        this.redisTemplate = redisTemplate;
        this.auditLogger = auditLogger;
        this.redisScript = new DefaultRedisScript<>();
        // Load the Lua script from the classpath.
        this.redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate-limit.lua")));
        // Set the expected result type of the script.
        this.redisScript.setResultType(Long.class);
    }

    /**
     * Intercepts incoming requests to apply rate limiting before they reach the controller.
     *
     * @param request  The HTTP request.
     * @param response The HTTP response.
     * @param handler  The handler for the request.
     * @return {@code true} if the request is allowed, {@code false} if it is rate-limited.
     * @throws Exception if an error occurs.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // Determine the client key for rate limiting (based on user or IP).
        String clientKey = getClientKey(request);
        long now = Instant.now().getEpochSecond();
        long refillSeconds = parseRefillPeriod(refillPeriod);

        // Get user authentication details.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        int effectiveCapacity = capacity;
        int effectiveRate = rate;

        // Apply more generous rate limits for authenticated users.
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            effectiveCapacity = userCapacity;
            effectiveRate = userRate;
        }

        // Execute the Lua script on the Redis server to check and update the rate limit.
        Long allowed = redisTemplate.execute(
                redisScript,
                Collections.singletonList(clientKey), // KEYS[1]
                String.valueOf(effectiveRate), // ARGV[1]
                String.valueOf(effectiveCapacity), // ARGV[2]
                String.valueOf(now), // ARGV[3]
                String.valueOf(refillSeconds), // ARGV[4]
                "1" // ARGV[5] - tokens to consume
                );

        String username = auth != null ? auth.getName() : "anonymous";
        boolean isAllowed = allowed != null && allowed == 1L;

        if (isAllowed) {
            // Add rate limit headers to the response.
            addRateLimitHeaders(response, clientKey, effectiveCapacity, effectiveRate);
            // Log the successful operation.
            if (auditLogger != null) {
                auditLogger.logOperation(username, "RATE_LIMIT_ALLOWED", clientKey, true);
            }
            return true; // Request is allowed.
        } else {
            // If rate-limited, send a 429 Too Many Requests response.
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests\"}");
            // Log the security event.
            if (auditLogger != null) {
                auditLogger.logSecurityEvent(username, "RATE_LIMIT_EXCEEDED", getClientIpAddress(request), false);
            }
            log.warn("Rate limit exceeded for client: {} (user: {})", clientKey, username);
            return false; // Request is blocked.
        }
    }

    /**
     * Generates a unique key for rate limiting based on the client's identity.
     * For authenticated users, the key is based on their username.
     * For anonymous users, the key is based on their IP address.
     *
     * @param request The HTTP request.
     * @return A unique key for the client.
     */
    private String getClientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return "rl:user:" + auth.getName();
        }
        return "rl:ip:" + getClientIpAddress(request);
    }

    /**
     * Retrieves the client's IP address from the request, considering proxy headers.
     *
     * @param request The HTTP request.
     * @return The client's IP address.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedForHeader = request.getHeader("X-Forwarded-For");
        if (xForwardedForHeader == null || xForwardedForHeader.isEmpty()) {
            String xRealIp = request.getHeader("X-Real-IP");
            if (xRealIp != null && !xRealIp.isEmpty()) {
                return xRealIp;
            }
            return request.getRemoteAddr();
        } else {
            // The X-Forwarded-For header can contain a comma-separated list of IPs.
            // The first IP in the list is the original client IP.
            return xForwardedForHeader.split(",")[0].trim();
        }
    }

    /**
     * Adds standard rate-limiting headers to the HTTP response to inform the client
     * about their current rate limit status.
     *
     * @param response The HTTP response.
     * @param key      The client's rate-limiting key.
     * @param capacity The total capacity of the token bucket.
     * @param rate     The refill rate of the token bucket.
     */
    private void addRateLimitHeaders(HttpServletResponse response, String key, int capacity, int rate) {
        try {
            // Retrieve the remaining tokens from Redis.
            String tokensStr = (String) redisTemplate.opsForHash().get(key, "tokens");
            int remainingTokens = tokensStr != null ? (int) Double.parseDouble(tokensStr) : capacity;
            // Calculate the time until the bucket is fully refilled.
            long resetSeconds =
                    Math.max(1, (long) Math.ceil((double) (capacity - remainingTokens) / Math.max(1, rate)));

            // Set the standard rate limit headers.
            response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, remainingTokens)));
            response.setHeader(
                    "X-RateLimit-Reset",
                    String.valueOf(Instant.now().plusSeconds(resetSeconds).getEpochSecond()));
            response.setHeader("Retry-After", String.valueOf(resetSeconds));
        } catch (Exception e) {
            log.debug("Failed to add rate limit headers", e);
        }
    }

    /**
     * Parses the refill period string (e.g., "1s", "1m", "1h") into seconds.
     *
     * @param period The refill period string from the configuration.
     * @return The refill period in seconds.
     */
    private long parseRefillPeriod(String period) {
        if (period.endsWith("s")) {
            return Long.parseLong(period.replace("s", ""));
        } else if (period.endsWith("m")) {
            return Long.parseLong(period.replace("m", "")) * 60;
        } else if (period.endsWith("h")) {
            return Long.parseLong(period.replace("h", "")) * 3600;
        }
        return 1; // Default to 1 second if the format is invalid.
    }
}
