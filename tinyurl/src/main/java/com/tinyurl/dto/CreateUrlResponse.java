package com.tinyurl.dto;

import java.time.OffsetDateTime;

public record CreateUrlResponse(
    String shortUrl,
    String shortCode,
    String originalUrl,
    OffsetDateTime expiresAt
) {
}
