package com.socialapi.dto;

import com.socialapi.entity.Post;
import lombok.*;

import java.time.LocalDateTime;
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostResponse {
    private  Long id;
    Long authorId;
    Post.AuthorType authorType;
    String content;
    LocalDateTime createdAt;
    Long viralityScore;

    public static PostResponse from(Post p, Long viralityScore) {
        return PostResponse.builder()
                .id(p.getId())
                .authorId(p.getAuthorId())
                .authorType(p.getAuthorType())
                .content(p.getContent())
                .createdAt(p.getCreatedAt())
                .viralityScore(viralityScore)
                .build();
    }

}
