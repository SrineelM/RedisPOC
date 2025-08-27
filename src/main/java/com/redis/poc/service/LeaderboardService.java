package com.redis.poc.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Manages a leaderboard using a Redis Sorted Set (ZSet).
 * A Sorted Set is an ideal data structure for a leaderboard because it maintains
 * a collection of unique members (users) ordered by an associated score.
 * This service provides methods to update user scores, retrieve top users,
 * and get a specific user's rank.
 */
@Service
public class LeaderboardService {

    // The key for the sorted set that stores the leaderboard.
    private static final String LEADERBOARD_KEY = "leaderboard";
    private final ZSetOperations<String, String> zSetOperations;

    /**
     * Constructs the LeaderboardService.
     * @param redisTemplate The RedisTemplate to interact with Redis.
     */
    public LeaderboardService(RedisTemplate<String, String> redisTemplate) {
        // opsForZSet() provides a convenient API for Redis ZSET commands.
        this.zSetOperations = redisTemplate.opsForZSet();
    }

    /**
     * Updates the score for a given user on the leaderboard.
     * This method uses the Redis ZADD command. If the user does not exist,
     * they are added to the leaderboard. If they already exist, their score is updated.
     *
     * @param userId The ID of the user (the member in the sorted set).
     * @param score The new score for the user.
     */
    public void updateUserScore(String userId, double score) {
        zSetOperations.add(LEADERBOARD_KEY, userId, score);
    }

    /**
     * Retrieves a set of users from the top of the leaderboard.
     * This method uses the Redis ZREVRANGE command to get a range of members
     * ordered from the highest score to the lowest.
     *
     * @param topN The number of top users to retrieve.
     * @return An ordered Set of user IDs representing the top users.
     */
    public Set<String> getTopUsers(int topN) {
        // ZREVRANGE returns members from highest to lowest score.
        // The range is 0-based, so to get the top N users, we need the range from 0 to N-1.
        return zSetOperations.reverseRange(LEADERBOARD_KEY, 0, topN - 1);
    }

    /**
     * Gets the rank of a specific user in the leaderboard.
     * The rank is 0-based, meaning the user with the highest score has rank 0.
     * This method uses the Redis ZREVRANK command.
     *
     * @param userId The ID of the user.
     * @return The 0-based rank of the user, or null if the user is not on the leaderboard.
     */
    public Long getUserRank(String userId) {
        // ZREVRANK returns the 0-based rank of a member in the sorted set,
        // ordered from highest to lowest score.
        return zSetOperations.reverseRank(LEADERBOARD_KEY, userId);
    }
}
