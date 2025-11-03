package com.redis.poc.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import redis.embedded.RedisServer;

/**
 * Configuration class that automatically starts an embedded Redis server for the 'local' profile.
 * This eliminates the need for external Redis installation during local development,
 * making it perfect for laptops with limited resources (8GB RAM).
 *
 * <p><b>Purpose:</b></p>
 * <ul>
 *   <li>Zero-setup Redis for local development</li>
 *   <li>Consistent development environment across all machines</li>
 *   <li>Offline development capability (no external dependencies)</li>
 *   <li>Resource-efficient embedded Redis instance (~100-200MB)</li>
 * </ul>
 *
 * <p><b>Technical Details:</b></p>
 * <ul>
 *   <li>Uses 'it.ozimov:embedded-redis' library</li>
 *   <li>Starts on application startup via @PostConstruct</li>
 *   <li>Stops on application shutdown via @PreDestroy</li>
 *   <li>Runs on port 6370 to avoid conflicts with system Redis (6379)</li>
 * </ul>
 *
 * <p><b>Limitations:</b></p>
 * <ul>
 *   <li>Single instance only (no cluster/sentinel support)</li>
 *   <li>Limited to ~100MB dataset (suitable for development)</li>
 *   <li>No persistence (data lost on restart)</li>
 *   <li>Some Redis modules may not be available</li>
 *   <li>NOT suitable for performance testing or load testing</li>
 * </ul>
 *
 * <p><b>When to Use:</b></p>
 * <ul>
 *   <li>Local development on 8GB RAM machines</li>
 *   <li>Quick prototyping and feature development</li>
 *   <li>Unit testing with real Redis (better than mocks)</li>
 *   <li>Offline development (trains, planes, etc.)</li>
 * </ul>
 *
 * <p><b>When NOT to Use:</b></p>
 * <ul>
 *   <li>Integration testing (use 'dev' profile with real Redis)</li>
 *   <li>Performance benchmarking (use 'dev' or 'prod' profile)</li>
 *   <li>Testing Redis cluster features</li>
 *   <li>Multi-instance scenarios</li>
 * </ul>
 *
 * @see <a href="https://github.com/ozimov/embedded-redis">Embedded Redis GitHub</a>
 */
@Configuration
@Profile("local") // Only active when 'local' profile is selected
@Slf4j
public class EmbeddedRedisConfig {

    /**
     * Redis port configuration from application-local.yml.
     * Default is 6370 to avoid conflicts with system Redis on 6379.
     */
    @Value("${spring.data.redis.port:6370}")
    private int redisPort;

    /**
     * The embedded Redis server instance.
     * Nullable because it may fail to start (port already in use, etc.).
     */
    private RedisServer redisServer;

    /**
     * Starts the embedded Redis server on application startup.
     *
     * <p>This method is automatically called by Spring after bean construction.
     * It attempts to start Redis on the configured port with error handling.</p>
     *
     * <p><b>Startup Process:</b></p>
     * <ol>
     *   <li>Checks if port is available</li>
     *   <li>Initializes RedisServer instance</li>
     *   <li>Starts the server</li>
     *   <li>Logs success or failure</li>
     * </ol>
     *
     * <p><b>Error Handling:</b></p>
     * <p>If the port is already in use (e.g., system Redis running on 6370),
     * the startup will fail with a clear error message. Solutions:</p>
     * <ul>
     *   <li>Change port in application-local.yml (e.g., 6371, 6372)</li>
     *   <li>Stop the conflicting Redis instance</li>
     *   <li>Switch to 'dev' profile with external Redis</li>
     * </ul>
     */
    @PostConstruct
    public void startRedis() {
        try {
            log.info("================================================");
            log.info("Starting Embedded Redis Server for 'local' profile");
            log.info("Port: {}", redisPort);
            log.info("Memory: ~100-200MB (minimal footprint)");
            log.info("Purpose: Local development only");
            log.info("Limitations: No persistence, single instance");
            log.info("================================================");

            // Initialize and start the embedded Redis server
            redisServer = new RedisServer(redisPort);
            redisServer.start();

            log.info("✓ Embedded Redis started successfully on port {}", redisPort);
            log.info("✓ Redis is ready for connections: localhost:{}", redisPort);
            log.info("✓ Test connection: redis-cli -p {}", redisPort);
            log.info("================================================");

        } catch (Exception e) {
            log.error("================================================");
            log.error("✗ Failed to start Embedded Redis on port {}", redisPort);
            log.error("✗ Error: {}", e.getMessage());
            log.error("================================================");
            log.error("SOLUTIONS:");
            log.error("1. Change port in application-local.yml: spring.data.redis.port=6371");
            log.error("2. Stop conflicting Redis: lsof -ti:6370 | xargs kill -9");
            log.error("3. Use 'dev' profile with external Redis: SPRING_PROFILES_ACTIVE=dev");
            log.error("================================================");

            // Rethrow to prevent application startup with non-functional Redis
            // This is intentional: fail-fast is better than silently failing
            throw new IllegalStateException(
                    "Embedded Redis failed to start. See logs above for solutions.", e);
        }
    }

    /**
     * Stops the embedded Redis server on application shutdown.
     *
     * <p>This method is automatically called by Spring during shutdown.
     * It ensures graceful cleanup of Redis resources.</p>
     *
     * <p><b>Shutdown Process:</b></p>
     * <ol>
     *   <li>Checks if Redis server is running</li>
     *   <li>Stops the server gracefully</li>
     *   <li>Releases port and resources</li>
     *   <li>Logs shutdown status</li>
     * </ol>
     *
     * <p><b>Data Persistence:</b></p>
     * <p>All data in embedded Redis is LOST on shutdown (no AOF/RDB persistence).
     * This is intentional for local development to ensure clean state on each startup.</p>
     */
    @PreDestroy
    public void stopRedis() {
        if (redisServer != null && redisServer.isActive()) {
            try {
                log.info("Stopping Embedded Redis Server...");
                redisServer.stop();
                log.info("✓ Embedded Redis stopped successfully");
                log.info("⚠ All data has been lost (no persistence in local mode)");
            } catch (Exception e) {
                log.error("✗ Error stopping Embedded Redis: {}", e.getMessage());
                // Don't rethrow - we're shutting down anyway
            }
        }
    }

    /**
     * Health check method to verify if embedded Redis is running.
     *
     * @return true if Redis is active, false otherwise
     */
    public boolean isRedisRunning() {
        return redisServer != null && redisServer.isActive();
    }

    /**
     * Gets the port number where embedded Redis is running.
     *
     * @return the Redis port number
     */
    public int getRedisPort() {
        return redisPort;
    }
}
