package com.socialapi.controller;

import com.socialapi.dto.*;
import com.socialapi.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /** POST /api/posts — create a new post (user or bot) */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse createPost(@Valid @RequestBody CreatePostRequest req) {
        return postService.createPost(req);
    }

    /** POST /api/posts/{postId}/comments — add a comment with guardrails */
    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(
            @PathVariable Long postId,
            @Valid @RequestBody CreateCommentRequest req
    ) {
        return postService.addComment(postId, req);
    }

    /** POST /api/posts/{postId}/like — human likes a post */
    @PostMapping("/{postId}/like")
    public LikeResponse likePost(
            @PathVariable Long postId,
            @Valid @RequestBody LikeRequest req
    ) {
        return postService.likePost(postId, req);
    }
}