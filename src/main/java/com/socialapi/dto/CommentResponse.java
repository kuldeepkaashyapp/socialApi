package com.socialapi.dto;

import com.socialapi.entity.Comment;
import com.socialapi.entity.Post;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CommentResponse {
    private Long id;
    private Long postId;
    private Long authorId;
    private Post.AuthorType authorType;
    private String content;
    private int depthLevel;
    private LocalDateTime createdAt;
    private Long viralityScore;
    private Long currentBotCount;

    public static CommentResponse from(Comment c, Long viralityScore, Long botCount) {
        return CommentResponse.builder()
                .id(c.getId())
                .postId(c.getPostId())
                .authorId(c.getAuthorId())
                .authorType(c.getAuthorType())
                .content(c.getContent())
                .depthLevel(c.getDepthLevel())
                .createdAt(c.getCreatedAt())
                .viralityScore(viralityScore)
                .currentBotCount(botCount)
                .build();
    }
}