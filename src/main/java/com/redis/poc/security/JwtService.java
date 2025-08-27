package com.redis.poc.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service responsible for generating and managing JSON Web Tokens (JWTs).
 * This service handles the creation of signed tokens with standard claims
 * and provides mechanisms for token revocation via JWT IDs (jti).
 */
@Service
public class JwtService {

    // The secret key used to sign the JWTs. It should be at least 32 characters long for HS256.
    @Value("${jwt.secret:mySecretKey}")
    private String jwtSecret;

    // The token's validity period in seconds. Defaults to 1 hour (3600 seconds).
    @Value("${jwt.expiration:3600}")
    private Long jwtExpirationSeconds;

    /**
     * Generates a standard JWT for a given username.
     *
     * @param username The subject of the token.
     * @return A signed JWT string.
     */
    public String generateToken(String username) {
        validateSecret();
        Map<String, Object> claims = new HashMap<>();
        // 'aud' (Audience) claim: Specifies that this token is intended for the "redis-poc" service.
        claims.put("aud", "redis-poc");
        return createToken(claims, username);
    }

    /**
     * Generates a JWT with a specific JWT ID (jti) claim.
     * The 'jti' is used to uniquely identify a token, which is essential for implementing token revocation.
     *
     * @param username The subject of the token.
     * @param jti      The unique JWT ID.
     * @return A signed JWT string with a 'jti' claim.
     */
    public String generateTokenWithId(String username, String jti) {
        validateSecret();
        Map<String, Object> claims = new HashMap<>();
        // 'aud' (Audience) claim: Specifies the intended recipient of the token.
        claims.put("aud", "redis-poc");
        // 'jti' (JWT ID) claim: Provides a unique identifier for the token to prevent replay attacks and allow revocation.
        claims.put("jti", jti);
        return createToken(claims, username);
    }

    /**
     * Generates a JWT with a randomly generated UUID as the JWT ID (jti).
     *
     * @param username The subject of the token.
     * @return A signed JWT string with a random 'jti'.
     */
    public String generateTokenWithRandomId(String username) {
        return generateTokenWithId(username, UUID.randomUUID().toString());
    }

    /**
     * Calculates the remaining time-to-live (TTL) for a token in seconds.
     *
     * @param token The JWT string.
     * @return The remaining validity time in seconds, or 0 if the token is invalid or expired.
     */
    public long getRemainingTtlSeconds(String token) {
        try {
            // Parse the token to extract its claims.
            var claims = io.jsonwebtoken.Jwts.parser().build().parseSignedClaims(token).getPayload();
            long expMs = claims.getExpiration().getTime();
            long now = System.currentTimeMillis();
            // Return the difference in seconds, ensuring it's not negative.
            return Math.max(0, (expMs - now) / 1000);
        } catch (Exception e) {
            // If parsing fails (e.g., invalid signature, expired), return 0.
            return 0;
        }
    }

    /**
     * Private helper method to construct the JWT.
     *
     * @param claims  A map of claims to include in the token's payload.
     * @param subject The subject of the token (typically the username).
     * @return A compact, signed JWT string.
     */
    private String createToken(Map<String, Object> claims, String subject) {
        validateSecret();
        // Create a secure key from the secret string using HMAC-SHA algorithm.
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        Date expiry = new Date(now + jwtExpirationSeconds * 1000);

        return Jwts.builder()
                .claims(claims)
                .issuer("redis-poc") // 'iss' (Issuer) claim
                .subject(subject) // 'sub' (Subject) claim
                .issuedAt(new Date(now)) // 'iat' (Issued At) claim
                .expiration(expiry) // 'exp' (Expiration) claim
                .signWith(key) // Sign the token with the generated key
                .compact();
    }

    /**
     * Validates that the JWT secret is not null and meets the minimum length requirement for HS256.
     */
    private void validateSecret() {
        // HS256 requires a secret key of at least 256 bits (32 bytes).
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException("JWT secret is too short; it must be at least 32 characters for HS256.");
        }
    }
}
