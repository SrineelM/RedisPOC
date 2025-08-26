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

/**
 * Configures the Redis Publish/Subscribe (Pub/Sub) mechanism.
 *
 * <p>This class sets up the necessary Spring beans to listen for messages on Redis channels. It
 * configures a listener for a custom application-specific topic as well as a listener for Redis
 * keyspace notifications, specifically for key expiration events.
 *
 * <p><b>Note:</b> To receive keyspace notifications (like 'expired'), you must enable them in your
 * Redis server configuration ({@code redis.conf}) or via the {@code CONFIG SET} command. For
 * example: {@code CONFIG SET notify-keyspace-events KEA}.
 */
@Configuration
public class RedisPubSubConfig {

    /**
     * Defines a bean for the main application-specific pub/sub channel topic.
     *
     * <p>This {@link ChannelTopic} represents a named channel that the application can use for
     * general-purpose messaging.
     *
     * @return A {@link ChannelTopic} instance for the "pubsub:channel" topic.
     */
    @Bean
    public ChannelTopic topic() {
        return new ChannelTopic("pubsub:channel");
    }

    /**
     * Defines a bean for the keyspace notification pattern for expired keys.
     *
     * <p>This {@link PatternTopic} listens for key expiration events across all Redis databases
     * ({@code __keyevent@*__:expired}). Using a bean makes the pattern reusable and centrally
     * managed.
     *
     * @return A {@link PatternTopic} for listening to key expiration events.
     */
    @Bean
    public PatternTopic keyExpiredTopic() {
        return new PatternTopic("__keyevent@*__:expired");
    }

    /**
     * Creates a {@link MessageListenerAdapter} for the custom message subscriber.
     *
     * <p>This adapter wraps the {@link RedisMessageSubscriber} POJO, allowing it to act as a message
     * listener without having to implement the {@code MessageListener} interface directly. By
     * default, it will invoke the {@code handleMessage} method on the delegate.
     *
     * @param subscriber The {@link RedisMessageSubscriber} bean that will process incoming messages.
     * @return A configured {@link MessageListenerAdapter}.
     */
    @Bean
    public MessageListenerAdapter messageListenerAdapter(RedisMessageSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber);
    }

    /**
     * Configures the central component for Redis message listening.
     *
     * <p>The {@link RedisMessageListenerContainer} is the workhorse that handles the low-level
     * details of listening for messages from Redis. It binds listeners to topics and dispatches
     * incoming messages to the appropriate listener.
     *
     * <p>This container is configured to listen for:
     * <ul>
     *   <li>Messages on the custom "pubsub:channel" via the {@code messageListenerAdapter}.</li>
     *   <li>Key expiration events via the dedicated {@link KeyExpiredListener}.</li>
     * </ul>
     *
     * @param connectionFactory The Redis connection factory.
     * @param messageListenerAdapter The adapter for the general-purpose subscriber.
     * @param topic The custom channel topic.
     * @param keyExpiredListener The listener specifically for key expiration events.
     * @param keyExpiredTopic The pattern topic for key expiration events.
     * @return A fully configured {@link RedisMessageListenerContainer}.
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
