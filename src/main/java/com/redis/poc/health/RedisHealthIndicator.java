package com.redis.poc.health;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A custom Spring Boot Actuator Health Indicator for Redis.
 *
 * This class provides a detailed health check for the Redis connection, going beyond
 * simple connectivity. It is automatically invoked by the /actuator/health endpoint.
 * It checks for basic connectivity (PING/PONG), measures latency, and reports key
 * performance metrics from the Redis server's INFO command.
 */
@Component
@Slf4j
public class RedisHealthIndicator extends AbstractHealthIndicator {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StatefulRedisConnection<String, String> connection;

    public RedisHealthIndicator(
            RedisTemplate<String, Object> redisTemplate, StatefulRedisConnection<String, String> connection) {
        this.redisTemplate = redisTemplate;
        this.connection = connection;
    }

    /**
     * Performs the actual health check logic.
     *
     * @param builder The {@link Health.Builder} to which status and details are added.
     */
    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            // 1. Measure the latency of a simple PING command.
            Instant start = Instant.now();
            String pingResult = connection.sync().ping();
            Duration latency = Duration.between(start, Instant.now());

            // 2. Check if the PING was successful.
            if (!"PONG".equals(pingResult)) {
                builder.down().withDetail("error", "Redis ping failed").withDetail("response", pingResult);
                return;
            }

            // 3. If successful, fetch detailed server information.
            // Note: The INFO command can have a minor performance impact.
            Properties info = redisTemplate
                    .getConnectionFactory()
                    .getConnection()
                    .serverCommands()
                    .info();

            // 4. Build the initial "UP" response with basic details.
            builder.up().withDetail("ping", "PONG").withDetail("latency", latency.toMillis() + "ms");

            // 5. Add key metrics from the INFO command to the health details.
            if (info != null) {
                builder.withDetail("redis_version", info.getProperty("redis_version"))
                        .withDetail("used_memory_human", info.getProperty("used_memory_human"))
                        .withDetail("connected_clients", info.getProperty("connected_clients"))
                        .withDetail("uptime_in_seconds", info.getProperty("uptime_in_seconds"));
            }

            // 6. Enhance the health status based on latency thresholds.
            if (latency.toMillis() > 1000) {
                // If latency is critical, downgrade the status to DOWN.
                builder.status(Status.DOWN)
                        .withDetail("warning", "High latency detected: " + latency.toMillis() + "ms");
            } else if (latency.toMillis() > 100) {
                // If latency is elevated, keep status UP but add a warning.
                builder.withDetail("warning", "Elevated latency: " + latency.toMillis() + "ms");
            }

        } catch (Exception e) {
            // 7. If any exception occurs, report the service as DOWN.
            log.error("Redis health check failed", e);
            builder.down().withDetail("error", e.getClass().getSimpleName()).withDetail("message", e.getMessage());
        }
    }
}
