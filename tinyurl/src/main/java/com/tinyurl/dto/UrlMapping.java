package com.tinyurl.dto;

import java.time.OffsetDateTime;

public record UrlMapping(
    Long id,
    String shortCode,
    String originalUrl,
    OffsetDateTime expiresAt,
    boolean explicitExpiry
) {
}
