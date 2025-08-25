package com.redis.poc.config;

import com.redis.poc.pubsub.KeyExpiredListener;
import com.redis.poc.pubsub.RedisMessageSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisPubSubConfig {

    /**
     * Defines the topic for our main pub/sub channel.
     */
    @Bean
    public ChannelTopic topic() {
        return new ChannelTopic("pubsub:channel");
    }

    /**
     * Best Practice: Defines the pattern for keyspace notifications as a bean.
     * This makes it reusable and avoids hardcoding the pattern string.
     */
    @Bean
    public PatternTopic keyExpiredTopic() {
        return new PatternTopic("__keyevent@*__:expired");
    }

    /**
     * Creates a message listener adapter for the main message subscriber.
     */
    @Bean
    public MessageListenerAdapter messageListenerAdapter(RedisMessageSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber);
    }

    /**
     * Configures the Redis message listener container.
     * It registers listeners for both the main pub/sub channel and for key expiration events.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter messageListenerAdapter,
            ChannelTopic topic,
            KeyExpiredListener keyExpiredListener, // Inject the new listener
            PatternTopic keyExpiredTopic
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Register the main message subscriber
        container.addMessageListener(messageListenerAdapter, topic);
        // Best Practice: Register the dedicated listener for key expiration events.
        container.addMessageListener(keyExpiredListener, keyExpiredTopic);

        return container;
    }
}
