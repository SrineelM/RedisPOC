package com.redis.poc.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.Collections;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Long> redisScript;

    @Value("${ratelimit.capacity}")
    private int capacity;

    @Value("${ratelimit.rate}")
    private int rate;

    @Value("${ratelimit.refill-period}")
    private String refillPeriod;

    public RateLimitInterceptor(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.redisScript = new DefaultRedisScript<>();
        this.redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate-limit.lua")));
        this.redisScript.setResultType(Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String key = "rl:" + request.getRemoteAddr();
        long now = Instant.now().getEpochSecond();
        long refillSeconds = Long.parseLong(refillPeriod.replace("s", ""));

        Long allowed = redisTemplate.execute(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(rate),
                String.valueOf(capacity),
                String.valueOf(now),
                String.valueOf(refillSeconds),
                "1"
        );

        if (allowed != null && allowed == 1L) {
            return true;
        } else {
            response.setStatus(429); // Too Many Requests
            response.getWriter().write("Rate limit exceeded.");
            return false;
        }
    }
}
