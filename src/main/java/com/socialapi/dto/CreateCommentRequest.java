package com.socialapi.dto;

import com.socialapi.entity.Post;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCommentRequest {

    @NotNull private Long authorId;
    @NotNull private Post.AuthorType authorType;
    @NotBlank private  String content;

    @Min(1)
    private int depthLevel;  // ← THIS IS THE MISSING FIELD

}
