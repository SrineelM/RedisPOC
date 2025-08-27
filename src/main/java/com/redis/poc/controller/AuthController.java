package com.redis.poc.controller;

import com.redis.poc.audit.AuditLogger;
import com.redis.poc.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private final JwtService jwtService;
    private final AuditLogger auditLogger;
    private final StringRedisTemplate stringRedisTemplate;

    public AuthController(
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuditLogger auditLogger,
            StringRedisTemplate stringRedisTemplate) {
        this.jwtService = jwtService;
        this.auditLogger = auditLogger;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Value("${security.auth.login.fail-threshold:5}")
    private int failThreshold;

    @Value("${security.auth.login.lockout-seconds:300}")
    private long lockoutSeconds;

    @Value("${jwt.revocation-namespace:global}")
    private String revocationNamespace;

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request) {

        String ipAddress = getClientIpAddress(request);

        // Simple authentication - in production, use a proper UserDetailsService
        // Lockout check
        if (isLocked(loginRequest.getUsername())) {
            return ResponseEntity.status(423)
                    .body(Map.of(
                            "error", "Locked",
                            "message", "Account temporarily locked due to repeated failures"));
        }
        if (isValidUser(loginRequest.getUsername(), loginRequest.getPassword())) {
            String token = jwtService.generateTokenWithRandomId(loginRequest.getUsername());
            resetFailures(loginRequest.getUsername());

            auditLogger.logSecurityEvent(loginRequest.getUsername(), "LOGIN_SUCCESS", ipAddress, true);

            return ResponseEntity.ok(Map.of("token", token, "type", "Bearer", "username", loginRequest.getUsername()));
        } else {
            auditLogger.logSecurityEvent(loginRequest.getUsername(), "LOGIN_FAILED", ipAddress, false);
            log.warn("Failed login attempt for user: {} from IP: {}", loginRequest.getUsername(), ipAddress);
            recordFailure(loginRequest.getUsername());

            return ResponseEntity.status(401)
                    .body(Map.of(
                            "error", "Unauthorized",
                            "message", "Invalid credentials"));
        }
    }

    @PostMapping("/revoke")
    public ResponseEntity<Map<String, String>> revokeToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing token"));
        }
        String token = authHeader.substring(7);
        try {
            var claims = io.jsonwebtoken.Jwts.parser()
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String jti = (String) claims.get("jti");
            if (jti == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Token has no jti"));
            }
            long ttl = jwtService.getRemainingTtlSeconds(token);
            // store per-jti key with TTL for natural expiry
            String key = revokedKey(jti);
            stringRedisTemplate.opsForValue().set(key, "1", java.time.Duration.ofSeconds(Math.max(ttl, 1)));
            auditLogger.logSecurityEvent(claims.getSubject(), "TOKEN_REVOKE", getClientIpAddress(request), true);
            return ResponseEntity.ok(Map.of("status", "revoked", "jti", jti));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "Invalid token"));
        }
    }

    private String revokedKey(String jti) {
        return "jwt:revoked:" + revocationNamespace + ":" + jti;
    }

    private String failureKey(String user) {
        return "auth:fail:user:" + user;
    }

    private String lockKey(String user) {
        return "auth:lock:user:" + user;
    }

    private boolean isLocked(String user) {
        Boolean has = stringRedisTemplate.hasKey(lockKey(user));
        return Boolean.TRUE.equals(has);
    }

    private void recordFailure(String user) {
        Long fails = stringRedisTemplate.opsForValue().increment(failureKey(user));
        stringRedisTemplate.expire(failureKey(user), java.time.Duration.ofMinutes(15));
        if (fails != null && fails >= failThreshold) {
            stringRedisTemplate.opsForValue().set(lockKey(user), "1", java.time.Duration.ofSeconds(lockoutSeconds));
        }
    }

    private void resetFailures(String user) {
        stringRedisTemplate.delete(failureKey(user));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @Valid @RequestBody RegisterRequest registerRequest, HttpServletRequest request) {

        String ipAddress = getClientIpAddress(request);

        // Simple registration - in production, check if user exists, validate email, etc.
        auditLogger.logSecurityEvent(registerRequest.getUsername(), "REGISTER_ATTEMPT", ipAddress, true);

        return ResponseEntity.ok(
                Map.of("message", "User registered successfully", "username", registerRequest.getUsername()));
    }

    private boolean isValidUser(String username, String password) {
        // Simple hardcoded authentication for demo
        // In production, use Spring Security's UserDetailsService
        return "demo".equals(username) && "password".equals(password)
                || "admin".equals(username) && "admin123".equals(password);
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedForHeader = request.getHeader("X-Forwarded-For");
        if (xForwardedForHeader == null || xForwardedForHeader.isEmpty()) {
            return request.getRemoteAddr();
        } else {
            return xForwardedForHeader.split(",")[0].trim();
        }
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        private String username;

        @NotBlank(message = "Password is required")
        @Size(min = 6, max = 100, message = "Password must be between 6 and 100 characters")
        private String password;
    }

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        private String username;

        @NotBlank(message = "Password is required")
        @Size(min = 6, max = 100, message = "Password must be between 6 and 100 characters")
        private String password;

        @NotBlank(message = "Email is required")
        private String email;
    }
}
