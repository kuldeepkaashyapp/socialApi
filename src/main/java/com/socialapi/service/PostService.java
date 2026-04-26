package com.socialapi.service;

import com.socialapi.dto.*;
import com.socialapi.entity.*;
import com.socialapi.repository.*;
import com.socialapi.service.RedisGuardrailService.InteractionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostService {

    private final PostRepository       postRepository;
    private final CommentRepository    commentRepository;
    private final PostLikeRepository   postLikeRepository;
    private final UserRepository       userRepository;
    private final BotRepository        botRepository;
    private final RedisGuardrailService guardrail;

    // ─────────────────────────────────────────────────────────────────────────
    // Create Post
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public PostResponse createPost(CreatePostRequest req) {
        Post post = Post.builder()
                .authorId(req.getAuthorId())
                .authorType(req.getAuthorType())
                .content(req.getContent())
                .build();
        post = postRepository.save(post);
        log.info("Post {} created by {} {}", post.getId(), post.getAuthorType(), post.getAuthorId());
        return PostResponse.from(post, 0L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Add Comment
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enforces Redis guardrails BEFORE committing to PostgreSQL:
     *  1. Vertical cap check (depth ≤ 20)                — stateless, no Redis write
     *  2. Horizontal cap check (bot_count ≤ 100)         — atomic Lua INCR
     *  3. Cooldown check  (bot ↔ human, 10 min)          — atomic SET NX EX
     *
     * If any guardrail fires, we throw BEFORE the @Transactional boundary
     * writes to the DB, keeping PostgreSQL clean.
     *
     * If the DB transaction fails AFTER we've already incremented bot_count
     * in Redis, we roll it back explicitly (compensating action).
     */
    @Transactional
    public CommentResponse addComment(Long postId, CreateCommentRequest req) {
        // Ensure post exists
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        boolean isBot = req.getAuthorType() == Post.AuthorType.BOT;

        // ── 1. Vertical cap ──────────────────────────────────────────────────
        if (!guardrail.isDepthAllowed(req.getDepthLevel())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Depth limit exceeded (max " + 20 + " levels)");
        }

        boolean botCountIncremented = false;

        if (isBot) {
            // ── 2. Horizontal cap (atomic Lua) ───────────────────────────────
            if (!guardrail.tryIncrementBotCount(postId)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Bot reply limit reached for this post (max 100)");
            }
            botCountIncremented = true;

            // ── 3. Cooldown cap — only meaningful when bot targets a human post
            if (post.getAuthorType() == Post.AuthorType.USER) {
                if (!guardrail.tryAcquireBotCooldown(req.getAuthorId(), post.getAuthorId())) {
                    // Roll back the horizontal counter we just incremented
                    guardrail.decrementBotCount(postId);
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                            "Bot cooldown active — cannot interact with this user again within 10 minutes");
                }
            }
        }

        try {
            // ── 4. Persist to PostgreSQL ─────────────────────────────────────
            Comment comment = Comment.builder()
                    .postId(postId)
                    .authorId(req.getAuthorId())
                    .authorType(req.getAuthorType())
                    .content(req.getContent())
                    .depthLevel(req.getDepthLevel())
                    .build();
            comment = commentRepository.save(comment);

            // ── 5. Virality score update ─────────────────────────────────────
            guardrail.addViralityPoints(postId,
                    isBot ? InteractionType.BOT_REPLY : InteractionType.HUMAN_COMMENT);

            // ── 6. Notification (bot → human owner) ─────────────────────────
            if (isBot && post.getAuthorType() == Post.AuthorType.USER) {
                Bot bot = botRepository.findById(req.getAuthorId()).orElse(null);
                String botName = bot != null ? bot.getName() : "Bot#" + req.getAuthorId();
                guardrail.handleBotInteractionNotification(
                        post.getAuthorId(),
                        botName + " replied to your post"
                );
            }

            log.info("Comment {} saved on post {} (depth {})", comment.getId(), postId, comment.getDepthLevel());
            return CommentResponse.from(comment,
                    guardrail.getViralityScore(postId),
                    guardrail.getBotCount(postId));

        } catch (Exception ex) {
            // Compensate: roll back the Redis bot_count increment on DB failure
            if (botCountIncremented) {
                guardrail.decrementBotCount(postId);
                log.warn("Rolled back bot_count for post {} after DB failure", postId);
            }
            throw ex;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Like Post
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public LikeResponse likePost(Long postId, LikeRequest req) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        // Idempotent — ignore duplicate likes
        if (postLikeRepository.existsByPostIdAndUserId(postId, req.getUserId())) {
            return new LikeResponse(postId, req.getUserId(), guardrail.getViralityScore(postId), "Already liked");
        }

        postLikeRepository.save(PostLike.builder()
                .postId(postId)
                .userId(req.getUserId())
                .build());

        guardrail.addViralityPoints(postId, InteractionType.HUMAN_LIKE);

        log.info("Post {} liked by user {}", postId, req.getUserId());
        return new LikeResponse(postId, req.getUserId(), guardrail.getViralityScore(postId), "Like recorded");
    }
}