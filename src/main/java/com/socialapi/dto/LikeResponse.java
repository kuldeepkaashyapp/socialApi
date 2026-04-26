package com.socialapi.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LikeResponse {
    private Long postId;
    private Long userId;
    private Long viralityScore;
    private String message;
}