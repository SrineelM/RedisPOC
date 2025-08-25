package com.redis.poc.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.function.Function;

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret:mySecretKey}")
    private String jwtSecret;

    @Value("${jwt.expiration:3600}")
    private Long jwtExpirationSeconds;

    @Value("${jwt.issuer:example-issuer}")
    private String expectedIssuer;

    @Value("${jwt.audience:example-audience}")
    private String expectedAudience;

    @Value("${jwt.revocation-namespace:global}")
    private String revocationNamespace;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate; // optional

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, 
                                  @NonNull HttpServletResponse response, 
                                  @NonNull FilterChain filterChain) throws ServletException, IOException {
        
        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String jwt = authHeader.substring(7);
            String username = extractUsername(jwt);
            if (isRevoked(jwt)) {
                log.warn("Rejected revoked token for user {}", username);
                filterChain.doFilter(request, response);
                return;
            }
            
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                if (validateToken(jwt, username)) {
                    // Audit log successful authentication
                    log.info("Successful JWT authentication for user: {} from IP: {}", 
                            username, getClientIpAddress(request));
                    
                    UserDetails userDetails = User.builder()
                            .username(username)
                            .password("")
                            .authorities(new ArrayList<>())
                            .build();
                    
                    UsernamePasswordAuthenticationToken authToken = 
                            new UsernamePasswordAuthenticationToken(userDetails, null, new ArrayList<>());
                    
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Audit log failed authentication
            log.warn("JWT authentication failed from IP: {} - Error: {}", 
                    getClientIpAddress(request), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    private Boolean validateToken(String token, String username) {
        final String extractedUsername = extractUsername(token);
        Claims claims = extractAllClaims(token);
        boolean issuerValid = expectedIssuer.equals(claims.getIssuer());
        boolean audienceValid = claims.getAudience() != null && claims.getAudience().contains(expectedAudience);
        if (!issuerValid || !audienceValid) {
            log.warn("JWT claim validation failed. issuerValid={}, audienceValid={}", issuerValid, audienceValid);
            return false;
        }
        return (extractedUsername.equals(username) && !isTokenExpired(token));
    }

    private boolean isRevoked(String token) {
        if (stringRedisTemplate == null) return false;
        try {
            Claims claims = extractAllClaims(token);
            String jti = (String) claims.get("jti");
            if (jti == null) return false;
            String key = "jwt:revoked:"+revocationNamespace+":"+jti;
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            return false; // fail-open on revocation check
        }
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedForHeader = request.getHeader("X-Forwarded-For");
        if (xForwardedForHeader == null) {
            return request.getRemoteAddr();
        } else {
            return xForwardedForHeader.split(",")[0];
        }
    }
}
