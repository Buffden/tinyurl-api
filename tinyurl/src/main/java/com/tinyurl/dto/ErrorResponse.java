package com.tinyurl.dto;

public record ErrorResponse(
    String code,
    String message
) {
}
