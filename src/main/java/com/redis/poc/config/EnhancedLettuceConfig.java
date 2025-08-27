package com.redis.poc.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

/**
 * Provides a highly customized, production-grade configuration for the Lettuce Redis client.
 * This class overrides the default Spring Boot auto-configuration to enable advanced features
 * like connection pooling, fine-grained timeout settings, and resilience patterns.
 * This configuration is only active when the "dev" Spring profile is enabled.
 */
@Configuration
@Profile("dev")
public class EnhancedLettuceConfig {

    // Injects Redis connection details from application.properties or environment variables.
    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${spring.redis.timeout:5s}")
    private Duration timeout;

    @Value("${spring.redis.connect-timeout:5s}")
    private Duration connectTimeout;

    /**
     * Configures the underlying resources for the Lettuce client, such as thread pools.
     * Tuning these thread pools can be important for optimizing performance under high load.
     * @return A shared ClientResources instance.
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.builder()
                .ioThreadPoolSize(4) // Default is Netty's default (2 * available processors)
                .computationThreadPoolSize(4) // Default is Netty's default
                .build();
    }

    /**
     * Creates a fully configured, low-level Lettuce RedisClient instance.
     * This bean defines the core behavior of the Redis client, including socket options,
     * timeouts, and reconnection strategies.
     * @param clientResources The shared resources (thread pools) for the client.
     * @return A configured RedisClient.
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(ClientResources clientResources) {
        // Configures low-level TCP socket settings.
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(connectTimeout)
                .keepAlive(true) // Enable TCP keep-alive
                .tcpNoDelay(true) // Disable Nagle's algorithm for lower latency
                .build();

        // Configures timeouts for Redis commands.
        TimeoutOptions timeoutOptions =
                TimeoutOptions.builder().fixedTimeout(timeout).build();

        // Main client options configuration.
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .timeoutOptions(timeoutOptions)
                .autoReconnect(true) // Enable auto-reconnect for resilience
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS) // Fail fast when disconnected
                .protocolVersion(ProtocolVersion.RESP3) // Use the latest Redis protocol
                .publishOnScheduler(true) // Improves performance for Pub/Sub operations
                .build();

        String redisUri = buildRedisUri();
        RedisClient client = RedisClient.create(clientResources, redisUri);
        client.setOptions(clientOptions);
        return client;
    }

    /**
     * Creates the main RedisConnectionFactory bean that Spring Data Redis will use.
     * This factory is configured to use a connection pool for high performance.
     * @param clientResources The shared resources for the client.
     * @return A pooled LettuceConnectionFactory.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory(ClientResources clientResources) {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(redisHost, redisPort);

        // Configuration for the connection pool using Apache Commons Pool2.
        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(20); // Max number of connections in the pool
        poolConfig.setMaxIdle(10); // Max number of idle connections
        poolConfig.setMinIdle(2); // Min number of idle connections to maintain
        poolConfig.setTestOnBorrow(true); // Validate connections before they are borrowed from the pool
        poolConfig.setBlockWhenExhausted(true); // Block when the pool is exhausted, rather than failing
        poolConfig.setMaxWait(timeout); // How long to wait for a connection when the pool is exhausted

        // Creates the pooling configuration for the Lettuce client.
        LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .clientResources(clientResources)
                .commandTimeout(timeout)
                .poolConfig(poolConfig)
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfig, clientConfig);
        // It's often recommended to not share the native connection for high-concurrency applications
        // to ensure each thread gets its own connection from the pool.
        factory.setShareNativeConnection(false);

        return factory;
    }

    /**
     * Provides a stateful Redis connection bean for direct Lettuce operations.
     * This is required for health checks and advanced use cases.
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public StatefulRedisConnection<String, String> statefulRedisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    /**
     * Helper method to construct the Redis connection URI from configuration properties.
     * @return A redis URI string (e.g., "redis://localhost:6379").
     */
    private String buildRedisUri() {
        return (sslEnabled ? "rediss://" : "redis://") + redisHost + ":" + redisPort;
    }
}
