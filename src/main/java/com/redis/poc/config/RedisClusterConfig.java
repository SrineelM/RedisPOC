package com.redis.poc.config;

import io.lettuce.core.cluster.RedisClusterClient;

import io.lettuce.core.resource.ClientResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Configures the connection to a Redis cluster using Lettuce.
 *
 * <p>This class provides the necessary beans for both low-level Lettuce client access and
 * high-level Spring Data Redis integration for a clustered Redis environment. It reads the cluster
 * node information from the application properties.
 */
@Configuration
public class RedisClusterConfig {

    /**
     * A comma-separated list of Redis cluster nodes (e.g., "host1:port1,host2:port2"). Injected
     * from the {@code spring.redis.cluster.nodes} property in the application configuration.
     * Defaults to "localhost:6379" if the property is not set.
     */
    @Value("${spring.redis.cluster.nodes:localhost:6379}")
    private String clusterNodes;

    /**
     * Creates a low-level {@link RedisClusterClient} bean.
     *
     * <p>This client is part of the core Lettuce library and can be used for direct, low-level
     * interaction with the Redis cluster. It uses the provided {@link ClientResources} which are
     * managed by Spring. It connects to the first node specified in {@code clusterNodes} and uses it
     * as a seed to discover the rest of the cluster topology.
     *
     * @param clientResources The shared client resources (e.g., thread pools) managed by Spring.
     * @return A configured {@link RedisClusterClient} instance.
     */
    @Bean
    public RedisClusterClient redisClusterClient(ClientResources clientResources) {
        List<String> nodes = Arrays.asList(clusterNodes.split(","));
        return RedisClusterClient.create(clientResources, "redis://" + nodes.get(0));
    }

    /**
     * Creates a {@link LettuceConnectionFactory} bean for Spring Data Redis.
     *
     * <p>This factory is the primary bean used by Spring Data Redis (e.g., {@code RedisTemplate},
     * repositories) and Spring's caching abstraction ({@code @Cacheable}) to connect to the Redis
     * cluster. It is configured with all the nodes specified in the {@code
     * spring.redis.cluster.nodes} property.
     *
     * @return A configured {@link LettuceConnectionFactory} for the Redis cluster.
     */
    @Bean
    public LettuceConnectionFactory clusterConnectionFactory() {
        List<String> nodes = Arrays.asList(clusterNodes.split(","));
        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(nodes);
        return new LettuceConnectionFactory(clusterConfig);
    }
}
