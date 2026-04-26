package com.socialapi.scheduler;

import com.socialapi.service.RedisGuardrailService;
import com.socialapi.service.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * NotificationSweeper — Phase 3 CRON Task.
 *
 * Runs every 5 minutes (300_000 ms).  For each user who has pending
 * notifications in Redis, pops all messages, logs a summarized notification,
 * and clears the list.
 *
 * In production this sweep would be every 15 minutes; the 5-minute interval
 * is specified in the assignment for testing convenience.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationSweeper {

    private final RedisGuardrailService guardrail;

    @Scheduled(fixedRate = 300_000) // every 5 minutes
    public void sweep() {
        log.info("[CRON SWEEPER] Running notification sweep...");

        Set<String> keys = guardrail.scanPendingNotifKeys();
        if (keys == null || keys.isEmpty()) {
            log.info("[CRON SWEEPER] No pending notifications found.");
            return;
        }

        int usersNotified = 0;
        for (String key : keys) {
            // Extract userId from key pattern "user:{id}:pending_notifs"
            try {
                String[] parts = key.split(":");
                long userId = Long.parseLong(parts[1]);
                guardrail.flushPendingNotifications(userId);
                usersNotified++;
            } catch (Exception e) {
                log.warn("[CRON SWEEPER] Failed to process key {}: {}", key, e.getMessage());
            }
        }

        log.info("[CRON SWEEPER] Sweep complete — notified {} user(s).", usersNotified);
    }
}