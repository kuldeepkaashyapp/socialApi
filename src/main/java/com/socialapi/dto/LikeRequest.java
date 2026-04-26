package com.socialapi.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;


@Data
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder

public class LikeRequest {
    @NotNull private  Long userId;



}
