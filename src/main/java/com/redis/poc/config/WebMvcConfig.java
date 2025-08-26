package com.redis.poc.config;

import com.redis.poc.ratelimit.EnhancedRateLimitInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures Spring Web MVC settings, specifically for registering custom interceptors.
 *
 * <p>This class implements {@link WebMvcConfigurer} to hook into the web layer's configuration
 * and register the application's {@link EnhancedRateLimitInterceptor}. The paths to be included
 * and excluded from rate limiting are configured externally via application properties.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * The custom rate limiting interceptor bean, injected via the constructor.
     */
    private final EnhancedRateLimitInterceptor rateLimitInterceptor;

    /**
     * An array of URL path patterns to which the rate limiter will be applied.
     * Injected from the {@code ratelimit.paths} property, defaulting to {@code /api/**}.
     */
    @Value("${ratelimit.paths:/api/**}")
    private String[] rateLimitedPaths;

    /**
     * An array of URL path patterns to be excluded from rate limiting.
     * Injected from the {@code ratelimit.excluded-paths} property, defaulting to actuator and public API endpoints.
     */
    @Value("${ratelimit.excluded-paths:/actuator/**,/api/public/**}")
    private String[] excludedPaths;

    /**
     * Constructs the WebMvcConfig with the required interceptor.
     *
     * @param rateLimitInterceptor The rate limiting interceptor to be registered.
     */
    public WebMvcConfig(EnhancedRateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    /**
     * Registers the custom interceptors with the application's interceptor registry.
     *
     * <p>This method adds the {@link EnhancedRateLimitInterceptor} and configures it to apply
     * only to the specified {@code rateLimitedPaths}, while ignoring the {@code excludedPaths}.
     *
     * @param registry The interceptor registry to add the new interceptor to.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(rateLimitedPaths)
                .excludePathPatterns(excludedPaths);
    }
}
