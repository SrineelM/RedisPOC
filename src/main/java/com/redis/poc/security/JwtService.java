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

@Service
public class JwtService {

    @Value("${jwt.secret:mySecretKey}")
    private String jwtSecret;

    @Value("${jwt.expiration:3600}")
    private Long jwtExpirationSeconds;

    public String generateToken(String username) {
        validateSecret();
        Map<String, Object> claims = new HashMap<>();
        claims.put("aud", "redis-poc");
        return createToken(claims, username);
    }

    // Simple revocation support: store jti in claims and allow external revocation via Redis set (not yet wired)
    public String generateTokenWithId(String username, String jti) {
        validateSecret();
        Map<String, Object> claims = new HashMap<>();
        claims.put("aud", "redis-poc");
        claims.put("jti", jti);
        return createToken(claims, username);
    }

    public String generateTokenWithRandomId(String username) {
        return generateTokenWithId(username, UUID.randomUUID().toString());
    }

    public long getRemainingTtlSeconds(String token) {
        try {
            var claims = io.jsonwebtoken.Jwts.parser().build().parseSignedClaims(token).getPayload();
            long expMs = claims.getExpiration().getTime();
            long now = System.currentTimeMillis();
            return Math.max(0, (expMs - now) / 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    private String createToken(Map<String, Object> claims, String subject) {
        validateSecret();
    SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    long now = System.currentTimeMillis();
    Date expiry = new Date(now + jwtExpirationSeconds * 1000);
    return Jwts.builder()
        .claims(claims) // modern style
        .issuer("redis-poc")
        .subject(subject)
        .issuedAt(new Date(now))
        .expiration(expiry)
        .signWith(key)
        .compact();
    }

    private void validateSecret() {
        if (jwtSecret == null || jwtSecret.length() < 32) { // ~256 bits for HS256
            throw new IllegalStateException("JWT secret too short; must be >= 32 characters for HS256");
        }
    }
}
