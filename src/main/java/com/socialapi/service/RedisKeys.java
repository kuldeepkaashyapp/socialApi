package com.socialapi.service;

public final class RedisKeys {

    private RedisKeys() {}

    // Virality score for a post — updated on every interaction
    public static String viralityScore(long postId) {
        return "post:" + postId + ":virality_score";
    }

    // Total bot comment count on a post (Horizontal Cap)
    public static String botCount(long postId) {
        return "post:" + postId + ":bot_count";
    }

    // Bot-to-human cooldown key (Cooldown Cap, TTL = 10 min)
    public static String botHumanCooldown(long botId, long humanId) {
        return "cooldown:bot_" + botId + ":human_" + humanId;
    }

    // Per-user notification cooldown key (TTL = 15 min)
    public static String userNotifCooldown(long userId) {
        return "notif_cooldown:user_" + userId;
    }

    // Redis List of pending notification strings for a user
    public static String userPendingNotifs(long userId) {
        return "user:" + userId + ":pending_notifs";
    }

    // Prefix for scanning pending notification lists
    public static String pendingNotifsPattern() {
        return "user:*:pending_notifs";
    }
}
