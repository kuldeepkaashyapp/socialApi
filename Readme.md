# Social API — Spring Boot Microservice

A high-performance Spring Boot 3.x API gateway with Redis-backed guardrails for bot interactions, virality scoring, and smart notification batching.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL 16 |
| Cache / State | Redis 7 |
| Build | Maven |

---

## Quick Start

### 1. Start infrastructure

```bash
docker-compose up -d
```

This starts PostgreSQL on port `5432` and Redis on port `6379`.

### 2. Run the application

```bash
./mvnw spring-boot:run
```

Hibernate will auto-create the schema on first run (`spring.jpa.hibernate.ddl-auto=update`).

### 3. Seed test data (optional)

```bash
# Create a user
curl -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"authorId": 1, "authorType": "USER", "content": "Hello world"}'
```

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/posts` | Create a post (user or bot) |
| POST | `/api/posts/{id}/comments` | Add a comment (enforces guardrails for bots) |
| POST | `/api/posts/{id}/like` | Like a post (human only) |

### Create Post

```json
POST /api/posts
{
  "authorId": 1,
  "authorType": "USER",
  "content": "My first post!"
}
```

### Add Comment (Bot)

```json
POST /api/posts/1/comments
{
  "authorId": 42,
  "authorType": "BOT",
  "content": "Great post!",
  "depthLevel": 1
}
```

### Like a Post

```json
POST /api/posts/1/like
{
  "userId": 5
}
```

---

## Architecture Overview

```
HTTP Request
    │
    ▼
PostController
    │
    ▼
PostService
    ├──▶ RedisGuardrailService  (gatekeeper — runs FIRST)
    │         ├── Vertical Cap check  (depth ≤ 20)
    │         ├── Horizontal Cap      (bot_count atomic Lua INCR)
    │         └── Cooldown Cap        (SET NX EX)
    │
    └──▶ PostgreSQL  (only written if all guardrails pass)
```

---

## Thread-Safety: How Atomic Locks Are Guaranteed

### Problem

Naive concurrency handling would look like this in Java:

```java
// BROKEN — classic TOCTOU race condition
long count = redis.get("post:1:bot_count");  // Step 1: READ
if (count < 100) {                           // Step 2: CHECK
    redis.incr("post:1:bot_count");          // Step 3: WRITE
    // ↑ Another thread can slip between Step 2 and Step 3!
}
```

If 200 threads all read `count = 99` simultaneously, all 200 pass the check, and the counter reaches 299.

A `synchronized` block or `ReentrantLock` would only protect within a **single JVM**. In a horizontally-scaled deployment with multiple app instances, the race persists.

### Solution: Atomic Lua Script via Redis EVAL

The Horizontal Cap is implemented as a **Lua script executed atomically by the Redis server**:

```lua
local current = tonumber(redis.call('GET', KEYS[1])) or 0
if current >= tonumber(ARGV[1]) then
    return 0        -- rejected
end
redis.call('INCR', KEYS[1])
return 1            -- allowed
```

**Why this is race-free:**

Redis is single-threaded in its command processing. When a Lua script is sent via `EVAL`, Redis executes the entire script as one uninterruptible unit — no other client command can execute between the `GET` and the `INCR`. This is guaranteed by the Redis specification and holds true across any number of application instances.

The Spring implementation uses `DefaultRedisScript<Long>` executed via `RedisTemplate.execute()`, which maps directly to Redis `EVAL`.

### Cooldown Cap: SET NX EX

```java
redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(600));
```

`SET key value NX EX seconds` is a single atomic Redis command. It sets the key **only if it does not already exist** and assigns a TTL in the same operation. No Lua script needed — the atomicity is built into the command itself.

### Compensating Action on DB Failure

If the PostgreSQL transaction fails **after** a Redis bot_count increment, we explicitly decrement the counter to keep Redis and the DB in sync:

```java
try {
    commentRepository.save(comment); // may throw
} catch (Exception ex) {
    if (botCountIncremented) {
        guardrail.decrementBotCount(postId); // compensate
    }
    throw ex;
}
```

---

## Redis Key Schema

| Key | Type | Purpose |
|---|---|---|
| `post:{id}:virality_score` | String (int) | Running virality score |
| `post:{id}:bot_count` | String (int) | Total bot comments (Horizontal Cap) |
| `cooldown:bot_{bid}:human_{hid}` | String, TTL 10 min | Bot↔Human cooldown |
| `notif_cooldown:user_{id}` | String, TTL 15 min | Per-user notification throttle |
| `user:{id}:pending_notifs` | List | Queued notification messages |

---

## Virality Score Points

| Interaction | Points |
|---|---|
| Bot Reply | +1 |
| Human Like | +20 |
| Human Comment | +50 |

---

## Guardrail Rules

| Rule | Limit | Redis Mechanism |
|---|---|---|
| Horizontal Cap | ≤ 100 bot replies / post | Atomic Lua INCR |
| Vertical Cap | depth ≤ 20 | Stateless validation |
| Cooldown Cap | Bot → Human, 1× per 10 min | SET NX EX 600 |

HTTP `429 Too Many Requests` is returned when any cap is exceeded.

---

## CRON Sweeper

The `NotificationSweeper` runs every **5 minutes** (configurable). It:

1. Scans Redis for all `user:*:pending_notifs` keys
2. For each user, pops all queued notification strings
3. Logs a summary: `"Bot X and [N] others interacted with your posts."`
4. Clears the Redis list

Example console output:

```
[PUSH NOTIFICATION] Sent to User 5: BotAlpha replied to your post
[CRON SWEEPER] Running notification sweep...
[SUMMARIZED PUSH NOTIFICATION] User 5: BotAlpha replied to your post and [3] others interacted with your posts.
```

---

## Statelessness Guarantee

No `HashMap`, `static` variable, or in-memory counter is used. Every piece of transient state lives in Redis:

- Virality scores → Redis String
- Bot counts → Redis String (mutated via Lua EVAL)
- Cooldowns → Redis String with TTL
- Pending notifications → Redis List

The Spring Boot application can be restarted, scaled horizontally, or crashed without losing guardrail state.