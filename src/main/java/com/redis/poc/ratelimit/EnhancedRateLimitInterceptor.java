package com.redis.poc.ratelimit;

import com.redis.poc.audit.AuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

import java.time.Instant;
import java.util.Collections;

@Component
@Slf4j
public class EnhancedRateLimitInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Long> redisScript;
    private final AuditLogger auditLogger;

    @Value("${ratelimit.capacity:10}")
    private int capacity;

    @Value("${ratelimit.rate:5}")
    private int rate;

    @Value("${ratelimit.refill-period:1s}")
    private String refillPeriod;

    @Value("${ratelimit.user-capacity:50}")
    private int userCapacity;

    @Value("${ratelimit.user-rate:25}")
    private int userRate;

    public EnhancedRateLimitInterceptor(RedisTemplate<String, Object> redisTemplate, AuditLogger auditLogger) {
        this.redisTemplate = redisTemplate;
        this.auditLogger = auditLogger;
        this.redisScript = new DefaultRedisScript<>();
        this.redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate-limit.lua")));
        this.redisScript.setResultType(Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientKey = getClientKey(request);
        long now = Instant.now().getEpochSecond();
        long refillSeconds = parseRefillPeriod(refillPeriod);

        // Determine rate limits based on authentication
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        int effectiveCapacity = capacity;
        int effectiveRate = rate;
        
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            effectiveCapacity = userCapacity;
            effectiveRate = userRate;
        }

        Long allowed = redisTemplate.execute(
                redisScript,
                Collections.singletonList(clientKey),
                String.valueOf(effectiveRate),
                String.valueOf(effectiveCapacity),
                String.valueOf(now),
                String.valueOf(refillSeconds),
                "1"
        );

        String username = auth != null ? auth.getName() : "anonymous";
        boolean isAllowed = allowed != null && allowed == 1L;

        if (isAllowed) {
            // Add rate limit headers
            addRateLimitHeaders(response, clientKey, effectiveCapacity, effectiveRate);
            auditLogger.logOperation(username, "RATE_LIMIT_ALLOWED", clientKey, true);
            return true;
        } else {
            // Rate limit exceeded
            response.setStatus(429); // Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests\"}");
            
            auditLogger.logSecurityEvent(username, "RATE_LIMIT_EXCEEDED", getClientIpAddress(request), false);
            log.warn("Rate limit exceeded for client: {} (user: {})", clientKey, username);
            return false;
        }
    }

    private String getClientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        // Prefer user-based rate limiting for authenticated users
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return "rl:user:" + auth.getName();
        }
        
        // Fall back to IP-based rate limiting
        return "rl:ip:" + getClientIpAddress(request);
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedForHeader = request.getHeader("X-Forwarded-For");
        if (xForwardedForHeader == null || xForwardedForHeader.isEmpty()) {
            String xRealIp = request.getHeader("X-Real-IP");
            if (xRealIp != null && !xRealIp.isEmpty()) {
                return xRealIp;
            }
            return request.getRemoteAddr();
        } else {
            return xForwardedForHeader.split(",")[0].trim();
        }
    }

    private void addRateLimitHeaders(HttpServletResponse response, String key, int capacity, int rate) {
        try {
            // Get current bucket state
            String tokensStr = (String) redisTemplate.opsForHash().get(key, "tokens");
            int remainingTokens = tokensStr != null ? (int) Double.parseDouble(tokensStr) : capacity;
            long resetSeconds = Math.max(1, (long) Math.ceil((double) (capacity - remainingTokens) / Math.max(1, rate)));
            
            response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, remainingTokens)));
            response.setHeader("X-RateLimit-Reset", String.valueOf(Instant.now().plusSeconds(resetSeconds).getEpochSecond()));
            response.setHeader("Retry-After", String.valueOf(resetSeconds));
        } catch (Exception e) {
            log.debug("Failed to add rate limit headers", e);
        }
    }

    private long parseRefillPeriod(String period) {
        if (period.endsWith("s")) {
            return Long.parseLong(period.replace("s", ""));
        } else if (period.endsWith("m")) {
            return Long.parseLong(period.replace("m", "")) * 60;
        } else if (period.endsWith("h")) {
            return Long.parseLong(period.replace("h", "")) * 3600;
        }
        return 1; // Default to 1 second
    }
}
