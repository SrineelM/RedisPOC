package com.redis.poc.config;

import com.redis.poc.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.context.annotation.Bean;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configures the application's web security settings using Spring Security.
 *
 * <p>This class defines the security filter chain, CORS policy, password encoding, and other
 * security-related beans. It is set up for a stateless, JWT-based authentication mechanism,
 * which is common for modern REST APIs.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Constructs the security configuration with the necessary dependencies.
     *
     * @param jwtAuthenticationFilter The custom filter to process JWTs in incoming requests.
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * Defines the main security filter chain that applies to all HTTP requests.
     *
     * @param http The {@link HttpSecurity} object to configure.
     * @return The configured {@link SecurityFilterChain}.
     * @throws Exception if an error occurs during configuration.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable()) // Disable CSRF protection, as it's not needed for stateless APIs
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // Configure session management to be stateless, as we are using JWTs
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // Permit all requests to public endpoints, actuator, and API documentation
                .requestMatchers("/api/public/**", "/actuator/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                // All other requests must be authenticated
                .anyRequest().authenticated()
            )
            .headers(headers -> headers
                // Sets a strict Content Security Policy
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
                // Prevents clickjacking by disallowing the page to be rendered in a frame
                .frameOptions(frame -> frame.sameOrigin())
                // Enforces HTTPS by telling browsers to only access the site via HTTPS for the next year
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
            )
            // Add the custom JWT filter before the standard username/password authentication filter
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Configures Cross-Origin Resource Sharing (CORS) for the application.
     *
     * <p>This bean defines which origins, headers, and methods are allowed. For production, it is
     * highly recommended to replace {@code addAllowedOriginPattern("*")} with a specific list of
     * allowed origins loaded from application properties to prevent security vulnerabilities.
     *
     * @return A {@link CorsConfigurationSource} with the defined CORS rules.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*"); // For local; restrict in production
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Creates a dedicated thread pool for asynchronous Redis operations.
     *
     * <p>This executor can be injected into services that need to perform non-blocking tasks,
     * such as asynchronous cache writes or pub/sub message handling. Using a fixed thread pool
     * based on available processors is a sensible default. For production, consider using a
     * {@code ThreadPoolTaskExecutor} for better monitoring and configuration options.
     *
     * @return An {@link Executor} bean for async tasks.
     */
    @Bean(name = "redisAsyncExecutor")
    public Executor redisAsyncExecutor() {
        return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    /**
     * Provides a password encoder bean for hashing and verifying passwords.
     *
     * <p>Uses {@link BCryptPasswordEncoder}, which is the industry standard for password hashing.
     * It includes a salt and is computationally intensive, making it resistant to brute-force attacks.
     *
     * @return A {@link PasswordEncoder} instance.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
