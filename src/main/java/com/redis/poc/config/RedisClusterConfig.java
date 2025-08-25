package com.redis.poc.config;

import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.resource.ClientResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.Arrays;
import java.util.List;

@Configuration
public class RedisClusterConfig {

    @Value("${spring.redis.cluster.nodes:localhost:6379}")
    private String clusterNodes;

    @Bean
    public RedisClusterClient redisClusterClient(ClientResources clientResources) {
        List<String> nodes = Arrays.asList(clusterNodes.split(","));
        return RedisClusterClient.create(clientResources, "redis://" + nodes.get(0));
    }

    @Bean
    public LettuceConnectionFactory clusterConnectionFactory() {
        List<String> nodes = Arrays.asList(clusterNodes.split(","));
        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(nodes);
        return new LettuceConnectionFactory(clusterConfig);
    }
}
