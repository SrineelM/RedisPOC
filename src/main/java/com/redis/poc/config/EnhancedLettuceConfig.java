package com.redis.poc.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.lettuce.LettuceCommandLatencyRecorder;
import io.micrometer.core.instrument.binder.lettuce.LettuceMetrics;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;

import java.time.Duration;

@Configuration
public class EnhancedLettuceConfig {

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
     * Enhanced ClientResources with observability and thread pool tuning
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources(MeterRegistry meterRegistry) {
        return DefaultClientResources.builder()
                .ioThreadPoolSize(4)  // Tune based on load
                .computationThreadPoolSize(4)  // Tune based on load
                // Configure micrometer metrics
                .build();
    }

    /**
     * Production-ready Redis client with comprehensive resilience patterns
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(ClientResources clientResources) {
        // Enhanced socket options with keepalive and TCP settings
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(connectTimeout)
                .keepAlive(true)
                .tcpNoDelay(true)
                .build();

        // Comprehensive timeout configuration
        TimeoutOptions timeoutOptions = TimeoutOptions.builder()
                .fixedTimeout(timeout)
                .build();

        // Production-ready client options
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .timeoutOptions(timeoutOptions)
                .autoReconnect(true)
                .cancelCommandsOnReconnectFailure(false)  // Keep commands in queue during reconnect
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .protocolVersion(ProtocolVersion.RESP3)  // Use latest protocol
                .requestQueueSize(1000)  // Configure request queue size
                .publishOnScheduler(true)  // Better performance for pub/sub
                .build();

        String redisUri = buildRedisUri();
        RedisClient client = RedisClient.create(clientResources, redisUri);
        client.setOptions(clientOptions);
        return client;
    }

    /**
     * Enhanced connection factory with connection pooling
     */
    @Bean
    public LettuceConnectionFactory lettuceConnectionFactory(RedisClient redisClient) {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(redisHost, redisPort);
        
        // Connection pool configuration
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolConfig = 
            new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(timeout);
        
        LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
            .clientOptions(redisClient.getOptions())
            .clientResources(redisClient.getResources())
            .commandTimeout(timeout)
            .poolConfig(poolConfig)
            .build();
        
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfig, clientConfig);
        factory.setValidateConnection(true);
        factory.setShareNativeConnection(false);  // Better for high concurrency
        
        return factory;
    }

    private String buildRedisUri() {
        StringBuilder uri = new StringBuilder();
        uri.append(sslEnabled ? "rediss://" : "redis://");
        uri.append(redisHost).append(":").append(redisPort);
        return uri.toString();
    }

    /**
     * Metrics integration for observability
     */
    @Bean
    public MeterRegistry meterRegistry() {
        // In a real application, you'd configure a specific registry
        // This is a placeholder for a proper implementation
        return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }
}
