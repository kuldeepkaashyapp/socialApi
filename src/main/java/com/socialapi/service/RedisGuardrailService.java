package com.socialapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * RedisGuardrailService owns ALL Redis-based guardrail logic.
 *
 * Thread-Safety Strategy
 * ----------------------
 * The Horizontal Cap (bot_count ≤ 100) is enforced with a single Lua script
 * executed atomically by the Redis server via EVAL.  Because Redis is
 * single-threaded in its command execution, the Lua script — which performs
 * GET + conditional INCR — is guaranteed to run without interleaving from
 * other clients.  This eliminates the classic TOCTOU (check-then-act) race
 * condition that would occur if we used two separate Java calls (GET, then
 * INCR) with a Java-level synchronized block or ReentrantLock, which would
 * only protect within a single JVM instance (breaking horizontal scaling).
 *
 * The Cooldown Cap uses SET NX EX — a single atomic Redis command — so it
 * is inherently race-free.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisGuardrailService {

    private static final int HORIZONTAL_CAP = 100;
    private static final int VERTICAL_CAP   = 20;
    private static final long BOT_COOLDOWN_SECONDS  = 10 * 60; // 10 min
    private static final long USER_NOTIF_COOLDOWN_S = 15 * 60; // 15 min

    // Virality point values
    private static final long POINTS_BOT_REPLY      =  1;
    private static final long POINTS_HUMAN_LIKE     = 20;
    private static final long POINTS_HUMAN_COMMENT  = 50;

    private final StringRedisTemplate redis;

    //
    // Virality Score
    //

    /** Called after any interaction to update the post's virality score. */
    public void addViralityPoints(long postId, InteractionType type) {
        long points = switch (type) {
            case BOT_REPLY      -> POINTS_BOT_REPLY;
            case HUMAN_LIKE     -> POINTS_HUMAN_LIKE;
            case HUMAN_COMMENT  -> POINTS_HUMAN_COMMENT;
        };
        redis.opsForValue().increment(RedisKeys.viralityScore(postId), points);
        log.debug("Virality score for post {} incremented by {} ({})", postId, points, type);
    }

    public Long getViralityScore(long postId) {
        String val = redis.opsForValue().get(RedisKeys.viralityScore(postId));
        return val == null ? 0L : Long.parseLong(val);
    }


    // Horizontal Cap  (≤ 100 bot replies per post)


    /**
     * Atomically checks whether adding one more bot comment is allowed, and if
     * so, increments the counter in the same Redis round-trip.
     *
     * The Lua script:
     *   1. Reads the current bot_count value.
     *   2. If it is already ≥ CAP → returns 0 (rejected).
     *   3. Otherwise increments and returns the new value (allowed).
     *
     * Atomicity guarantee: Redis executes Lua scripts inside its single event
     * loop tick; no other command can execute between the GET and the INCR.
     *
     * @return true if the bot comment is allowed (counter was below cap and
     *         has now been incremented), false if the cap was already reached.
     */
    public boolean tryIncrementBotCount(long postId) {
        // language=lua
        String luaScript = """
            local current = tonumber(redis.call('GET', KEYS[1])) or 0
            if current >= tonumber(ARGV[1]) then
                return 0
            end
            redis.call('INCR', KEYS[1])
            return 1
            """;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
        Long result = redis.execute(
                script,
                List.of(RedisKeys.botCount(postId)),
                String.valueOf(HORIZONTAL_CAP)
        );
        boolean allowed = result != null && result == 1L;
        log.debug("Horizontal cap check for post {}: {}", postId, allowed ? "ALLOWED" : "REJECTED");
        return allowed;
    }

    /** Rolls back a previously incremented bot counter (e.g., on DB transaction failure). */
    public void decrementBotCount(long postId) {
        redis.opsForValue().decrement(RedisKeys.botCount(postId));
        log.debug("Rolled back bot_count for post {}", postId);
    }

    public Long getBotCount(long postId) {
        String val = redis.opsForValue().get(RedisKeys.botCount(postId));
        return val == null ? 0L : Long.parseLong(val);
    }


    // Vertical Cap  (depth ≤ 20)


    /**
     * Stateless check — depth_level is stored in PostgreSQL on the Comment
     * entity.  We validate here before any Redis or DB write.
     */
    public boolean isDepthAllowed(int depthLevel) {
        return depthLevel <= VERTICAL_CAP;
    }


    // Cooldown Cap  (bot human, once per 10 min)

    /**
     * Attempts to set the cooldown key with NX (only if NOT exists) and an
     * automatic TTL.  SET NX EX is atomic in Redis — no race possible.
     *
     * @return true if the bot is allowed to interact (key was absent, now set),
     *         false if the cooldown is still active (key already exists).
     */
    public boolean tryAcquireBotCooldown(long botId, long humanId) {
        Boolean set = redis.opsForValue().setIfAbsent(
                RedisKeys.botHumanCooldown(botId, humanId),
                "1",
                Duration.ofSeconds(BOT_COOLDOWN_SECONDS)
        );
        boolean allowed = Boolean.TRUE.equals(set);
        log.debug("Cooldown check bot {} → human {}: {}", botId, humanId, allowed ? "ALLOWED" : "BLOCKED");
        return allowed;
    }


    // Notification Throttler


    /**
     * Handles notification routing when a bot interacts with a user's content.
     *
     * - If no cooldown active → log push notification + set 15-min cooldown.
     * - If cooldown active → push to Redis List for later batching.
     */
    public void handleBotInteractionNotification(long userId, String notificationMessage) {
        String cooldownKey = RedisKeys.userNotifCooldown(userId);
        Boolean isFirstNotif = redis.opsForValue().setIfAbsent(
                cooldownKey, "1", Duration.ofSeconds(USER_NOTIF_COOLDOWN_S)
        );

        if (Boolean.TRUE.equals(isFirstNotif)) {
            // No recent notification — send immediately
            log.info("[PUSH NOTIFICATION] Sent to User {}: {}", userId, notificationMessage);
        } else {
            // Within cooldown window — enqueue for batch sweep
            redis.opsForList().rightPush(RedisKeys.userPendingNotifs(userId), notificationMessage);
            log.debug("Notification queued for user {}: {}", userId, notificationMessage);
        }
    }

    /**
     * Called by the CRON sweeper.  Pops all pending notifications for a user,
     * logs a summary, then leaves the list empty.
     */
    public void flushPendingNotifications(long userId) {
        String listKey = RedisKeys.userPendingNotifs(userId);
        List<String> pending = redis.opsForList().range(listKey, 0, -1);
        if (pending == null || pending.isEmpty()) return;

        redis.delete(listKey);

        String first = pending.get(0);
        int others = pending.size() - 1;
        String summary = others > 0
                ? first + " and [" + others + "] others interacted with your posts."
                : first;

        log.info("[SUMMARIZED PUSH NOTIFICATION] User {}: {}", userId, summary);
    }


    // Utility


    /** Returns all Redis keys matching the pending-notifications pattern. */
    public java.util.Set<String> scanPendingNotifKeys() {
        return redis.keys(RedisKeys.pendingNotifsPattern());
    }


    // Interaction type enum

    public enum InteractionType {
        BOT_REPLY,
        HUMAN_LIKE,
        HUMAN_COMMENT
    }
}