package com.tinyurl.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUrlRequest(
    @NotBlank(message = "INVALID_URL")
    @Size(max = 2048, message = "INVALID_URL")
    String url,

    @Positive(message = "INVALID_EXPIRY")
    @Max(value = 3650, message = "INVALID_EXPIRY")
    Integer expiresInDays
) {
}
