package com.redis.poc.config;

import com.redis.poc.ratelimit.EnhancedRateLimitInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final EnhancedRateLimitInterceptor rateLimitInterceptor;

    @Value("${ratelimit.paths:/api/**}")
    private String[] rateLimitedPaths;

    @Value("${ratelimit.excluded-paths:/actuator/**,/api/public/**}")
    private String[] excludedPaths;

    public WebMvcConfig(EnhancedRateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(rateLimitedPaths)
                .excludePathPatterns(excludedPaths);
    }
}
