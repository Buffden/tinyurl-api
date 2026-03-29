package com.tinyurl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "tinyurl")
public record AppProperties(
    String baseUrl,
    String frontendUrl,
    Integer defaultExpiryDays,
    Integer shortCodeMinLength,
    Cors cors
) {
    public record Cors(List<String> allowedOrigins) {}
}
