package com.redis.poc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Unified Redis configuration supporting both single Redis setup and read/write separation.
 *
 * <p>This class provides comprehensive Redis configuration including:
 * <ul>
 *   <li><b>Primary RedisTemplate:</b> For general Redis operations with advanced serialization</li>
 *   <li><b>Read/Write Templates:</b> Conditional beans for read/write separation when separate connection factories exist</li>
 *   <li><b>CacheManager:</b> Configured for Spring's @Cacheable annotations with consistent serialization</li>
 *   <li><b>Custom ObjectMapper:</b> Optimized for Redis with Java 8 time support and type safety</li>
 * </ul>
 *
 * <p>The configuration automatically adapts:
 * <ul>
 *   <li>Single Redis instance: Uses primary RedisTemplate only</li>
 *   <li>Read/Write separation: Creates additional read-only and write-only templates when corresponding connection factories are available</li>
 * </ul>
 *
 * <p>All RedisTemplates use consistent serialization for data integrity across different access patterns.
 */
@Configuration
public class RedisConfig {

    /**
     * Creates a custom {@link ObjectMapper} bean specifically for Redis serialization.
     *
     * <p>This ObjectMapper is configured with best practices for Redis:
     * <ul>
     *   <li><b>JavaTimeModule:</b> Ensures correct serialization/deserialization of Java 8 Date and Time API objects (e.g., {@code Instant}, {@code LocalDateTime}).</li>
     *   <li><b>Polymorphic Type Validator:</b> Enables default typing, which stores the object's class name in the JSON. This is crucial for reliably deserializing complex or polymorphic objects without {@code ClassCastException}s. Typing is restricted to the project's base package ({@code com.redis.poc}) as a security measure to prevent gadget attacks.</li>
     * </ul>
     *
     * @return A configured {@link ObjectMapper} for Redis.
     */
    @Bean("redisObjectMapper")
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.redis.poc")
                .build();
        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);
        return mapper;
    }

    /**
     * Configures the primary bean for interacting with Redis.
     *
     * <p>This {@link RedisTemplate} is configured to use explicit, robust serializers to ensure data consistency and readability in Redis.
     * <ul>
     *   <li><b>Key Serializer:</b> {@link StringRedisSerializer} makes keys human-readable in the Redis CLI.</li>
     *   <li><b>Value Serializer:</b> {@link GenericJackson2JsonRedisSerializer} uses the custom {@code redisObjectMapper} to store values as rich JSON, preserving type information.</li>
     * </ul>
     * This setup applies to both standard key-value operations and hash operations.
     *
     * @param connectionFactory The Redis connection factory provided by Spring Boot auto-configuration.
     * @param redisObjectMapper The custom ObjectMapper bean for JSON serialization.
     * @return A fully configured {@link RedisTemplate} instance.
     */
    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper redisObjectMapper) {
        return createRedisTemplate(connectionFactory, redisObjectMapper);
    }

    /**
     * Creates a read-only RedisTemplate for read/write separation scenarios.
     * Only created when a readConnectionFactory bean is available.
     *
     * @param readConnectionFactory The read-specific connection factory
     * @param redisObjectMapper The custom ObjectMapper bean for JSON serialization
     * @return A RedisTemplate configured for read operations
     */
    @Bean
    @Qualifier("readOnlyRedisTemplate")
    @ConditionalOnBean(name = "readConnectionFactory")
    public RedisTemplate<String, Object> readOnlyRedisTemplate(
            @Qualifier("readConnectionFactory") RedisConnectionFactory readConnectionFactory,
            ObjectMapper redisObjectMapper) {
        return createRedisTemplate(readConnectionFactory, redisObjectMapper);
    }

    /**
     * Creates a write-only RedisTemplate for read/write separation scenarios.
     * Only created when a writeConnectionFactory bean is available.
     *
     * @param writeConnectionFactory The write-specific connection factory
     * @param redisObjectMapper The custom ObjectMapper bean for JSON serialization
     * @return A RedisTemplate configured for write operations
     */
    @Bean
    @Qualifier("writeRedisTemplate")
    @ConditionalOnBean(name = "writeConnectionFactory")
    public RedisTemplate<String, Object> writeRedisTemplate(
            @Qualifier("writeConnectionFactory") RedisConnectionFactory writeConnectionFactory,
            ObjectMapper redisObjectMapper) {
        return createRedisTemplate(writeConnectionFactory, redisObjectMapper);
    }

    /**
     * Helper method to create consistently configured RedisTemplate instances.
     * Ensures all RedisTemplates use the same serialization strategy.
     *
     * @param connectionFactory The connection factory to use
     * @param redisObjectMapper The ObjectMapper for serialization
     * @return A configured RedisTemplate
     */
    private RedisTemplate<String, Object> createRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper redisObjectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper));
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper));
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Configures the {@link CacheManager} for Spring's caching abstraction (e.g., {@code @Cacheable}).
     *
     * <p>This bean ensures that data written via {@code @Cacheable} annotations uses the same serialization
     * strategy as the {@link RedisTemplate}, preventing data format inconsistencies.
     *
     * <p>It defines a default cache configuration with a 10-minute Time-To-Live (TTL) and disables caching of
     * {@code null} values, which is a common production requirement. It also demonstrates how to provide
     * specific configurations for different caches, allowing for fine-grained control over TTLs (e.g., short-lived
     * vs. long-lived caches).
     *
     * @param connectionFactory The Redis connection factory.
     * @param redisObjectMapper The custom ObjectMapper for value serialization.
     * @return A configured {@link RedisCacheManager}.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper redisObjectMapper) {
    RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(10))
        .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper)))
        .disableCachingNullValues();

    // Per-cache TTL overrides
    RedisCacheConfiguration shortLived = defaultConfig.entryTtl(Duration.ofSeconds(30));
    RedisCacheConfiguration longLived = defaultConfig.entryTtl(Duration.ofHours(1));

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaultConfig)
        .withCacheConfiguration("products:recent", shortLived)
        .withCacheConfiguration("products:details", longLived)
        .build();
    }
}
