package com.tinyurl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tinyurl")
public record AppProperties(
    String baseUrl,
    Integer defaultExpiryDays,
    Integer shortCodeMinLength
) {
}
