package com.socialapi.dto;

import com.socialapi.entity.Post;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePostRequest {
    @NotNull private Long authorId;
    @NotNull
    private Post.AuthorType authorType;
    @NotBlank
    private String content;
}