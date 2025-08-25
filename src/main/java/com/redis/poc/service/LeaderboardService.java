package com.redis.poc.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class LeaderboardService {

    private static final String LEADERBOARD_KEY = "leaderboard";
    private final ZSetOperations<String, String> zSetOperations;

    public LeaderboardService(RedisTemplate<String, String> redisTemplate) {
        this.zSetOperations = redisTemplate.opsForZSet();
    }

    /**
     * Updates the score for a given user on the leaderboard.
     * If the user does not exist, they are added to the leaderboard.
     * If they exist, their score is updated.
     * @param userId The ID of the user.
     * @param score The new score for the user.
     */
    public void updateUserScore(String userId, double score) {
        zSetOperations.add(LEADERBOARD_KEY, userId, score);
    }

    /**
     * Retrieves a set of users from the top of the leaderboard.
     * The results are ordered from the highest score to the lowest.
     * @param topN The number of top users to retrieve.
     * @return A Set of user IDs representing the top users.
     */
    public Set<String> getTopUsers(int topN) {
        // ZREVRANGE returns members from highest to lowest score
        return zSetOperations.reverseRange(LEADERBOARD_KEY, 0, topN - 1);
    }

    /**
     * Gets the rank of a specific user in the leaderboard.
     * The rank is 0-based (the top user has rank 0).
     * @param userId The ID of the user.
     * @return The rank of the user, or null if the user is not on the leaderboard.
     */
    public Long getUserRank(String userId) {
        // ZREVRANK returns the 0-based rank from highest to lowest score
        return zSetOperations.reverseRank(LEADERBOARD_KEY, userId);
    }
}
