package com.redis.poc.controller;

import com.redis.poc.service.LeaderboardService;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    public LeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    /**
     * Endpoint to update a user's score on the leaderboard.
     * @param userId The ID of the user.
     * @param score The user's new score.
     * @return A confirmation message.
     */
    @PostMapping("/score")
    public String updateUserScore(@RequestParam String userId, @RequestParam double score) {
        leaderboardService.updateUserScore(userId, score);
        return "Score updated for user " + userId;
    }

    /**
     * Endpoint to retrieve the top N users from the leaderboard.
     * @param n The number of top users to retrieve.
     * @return A Set of user IDs.
     */
    @GetMapping("/top/{n}")
    public Set<String> getTopUsers(@PathVariable int n) {
        return leaderboardService.getTopUsers(n);
    }

    /**
     * Endpoint to get the rank of a specific user.
     * @param userId The ID of the user.
     * @return The 0-based rank of the user.
     */
    @GetMapping("/rank/{userId}")
    public Long getUserRank(@PathVariable String userId) {
        return leaderboardService.getUserRank(userId);
    }
}
