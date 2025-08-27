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

/**
 * A Spring Security filter that intercepts incoming requests to validate JWTs.
 * This filter runs once per request and is responsible for authenticating users
 * by parsing the 'Authorization' header, validating the JWT, and setting the
 * user's authentication details in the SecurityContext. It also integrates
 * with Redis to check for token revocation.
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // --- JWT Configuration Properties ---
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

    // Optional Redis template for token revocation. If not present, revocation check is skipped.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    /**
     * The main filter logic. It extracts the JWT from the request, validates it,
     * checks for revocation, and sets the authentication in the security context.
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                  @NonNull HttpServletResponse response,
                                  @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // If the header is missing or doesn't start with "Bearer ", pass the request on.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            final String jwt = authHeader.substring(7);
            final String username = extractUsername(jwt);

            // --- Revocation Check ---
            // Before validating, check if the token has been revoked (e.g., due to logout).
            if (isRevoked(jwt)) {
                log.warn("Rejected revoked JWT for user '{}'", username);
                filterChain.doFilter(request, response); // Continue chain without authentication
                return;
            }

            // If username is present and no authentication is currently in the context...
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // ...validate the token's signature, expiration, and claims.
                if (validateToken(jwt, username)) {
                    // Audit log for successful authentication.
                    log.info("Successful JWT authentication for user: {} from IP: {}",
                            username, getClientIpAddress(request));

                    // Create UserDetails object for Spring Security.
                    UserDetails userDetails = User.builder()
                            .username(username)
                            .password("") // Password is not needed as we use JWTs
                            .authorities(new ArrayList<>()) // Set authorities/roles here if needed
                            .build();

                    // Create an authentication token.
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                    // Set the authentication in the security context.
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Audit log for any failed authentication attempt.
            log.warn("JWT authentication failed from IP: {} - Error: {}",
                    getClientIpAddress(request), e.getMessage());
        }

        // Continue the filter chain.
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the username (subject) from the JWT claims.
     */
    private String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * A generic function to extract a specific claim from the JWT.
     */
    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Parses the JWT and returns all its claims. Verifies the token's signature.
     */
    private Claims extractAllClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * Validates the JWT against the username and checks for expiration and claim correctness.
     * @return true if the token is valid, false otherwise.
     */
    private Boolean validateToken(String token, String username) {
        final String extractedUsername = extractUsername(token);
        final Claims claims = extractAllClaims(token);

        // --- Claims Validation ---
        final boolean isIssuerValid = expectedIssuer.equals(claims.getIssuer());
        final boolean isAudienceValid = claims.getAudience() != null && claims.getAudience().contains(expectedAudience);

        if (!isIssuerValid || !isAudienceValid) {
            log.warn("JWT claim validation failed. IssuerValid={}, AudienceValid={}", isIssuerValid, isAudienceValid);
            return false;
        }

        // --- Subject and Expiration Validation ---
        return (extractedUsername.equals(username) && !isTokenExpired(token));
    }

    /**
     * Checks if a JWT has been revoked by looking up its JTI (JWT ID) in Redis.
     * @return true if the token's JTI is found in the revocation list, false otherwise.
     */
    private boolean isRevoked(String token) {
        // If Redis is not configured, cannot check for revocation. Fail open.
        if (stringRedisTemplate == null) {
            return false;
        }
        try {
            Claims claims = extractAllClaims(token);
            String jti = claims.get("jti", String.class);
            // If the token has no JTI, it cannot be checked for revocation.
            if (jti == null) {
                return false;
            }
            // Construct the Redis key for the revoked JTI.
            String key = "jwt:revoked:" + revocationNamespace + ":" + jti;
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            log.error("Error during JWT revocation check", e);
            return false; // Fail open: If revocation check fails, treat token as not revoked.
        }
    }

    /**
     * Checks if the token's expiration time is in the past.
     */
    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    /**
     * Extracts the expiration date from the JWT.
     */
    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Retrieves the client's IP address from the request, considering the X-Forwarded-For header.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedForHeader = request.getHeader("X-Forwarded-For");
        if (xForwardedForHeader == null) {
            return request.getRemoteAddr();
        } else {
            // The X-Forwarded-For header can contain a comma-separated list of IPs.
            // The first one is the original client.
            return xForwardedForHeader.split(",")[0];
        }
    }
}
