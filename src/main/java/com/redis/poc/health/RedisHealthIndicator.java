
package com.redis.poc.health;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

@Component
@Slf4j
public class RedisHealthIndicator extends AbstractHealthIndicator {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StatefulRedisConnection<String, String> connection;

    public RedisHealthIndicator(RedisTemplate<String, Object> redisTemplate, 
                               StatefulRedisConnection<String, String> connection) {
        this.redisTemplate = redisTemplate;
        this.connection = connection;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            Instant start = Instant.now();
            
            // Test basic connectivity
            String pingResult = connection.sync().ping();
            
            Duration latency = Duration.between(start, Instant.now());
            
            if (!"PONG".equals(pingResult)) {
                builder.down()
                       .withDetail("error", "Redis ping failed")
                       .withDetail("response", pingResult);
                return;
            }

            // Get Redis info for additional health metrics
            Properties info = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .serverCommands()
                    .info();

            builder.up()
                   .withDetail("ping", "PONG")
                   .withDetail("latency", latency.toMillis() + "ms");

            // Add key Redis metrics
            if (info != null) {
                builder
                    .withDetail("redis_version", info.getProperty("redis_version"))
                    .withDetail("used_memory_human", info.getProperty("used_memory_human"))
                    .withDetail("connected_clients", info.getProperty("connected_clients"))
                    .withDetail("uptime_in_seconds", info.getProperty("uptime_in_seconds"));
            }

            // Check if latency is acceptable (warn if > 100ms, down if > 1000ms)
            if (latency.toMillis() > 1000) {
                builder
                    .status("DOWN")
                    .withDetail("warning", "High latency detected: " + latency.toMillis() + "ms");
            } else if (latency.toMillis() > 100) {
                builder
                    .status("UP")
                    .withDetail("warning", "Elevated latency: " + latency.toMillis() + "ms");
            }

        } catch (Exception e) {
            log.error("Redis health check failed", e);
            builder.down()
                   .withDetail("error", e.getClass().getSimpleName())
                   .withDetail("message", e.getMessage());
        }
    }
}
